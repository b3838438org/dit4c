package dit4c.scheduler.ssh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Executors

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.sys.process.BasicIO
import scala.util.Random

import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.CommandFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.KeySetPublickeyAuthenticator
import org.apache.sshd.server.shell.InvertedShellWrapper
import org.apache.sshd.server.shell.ProcessShell
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.specs2.ScalaCheck
import org.specs2.concurrent.ExecutionEnv
import org.specs2.execute.AsResult
import org.specs2.matcher.FileMatchers
import org.specs2.mutable.Specification
import org.specs2.scalacheck.Parameters
import org.specs2.specification.Fixture

import dit4c.scheduler.runner.CommandExecutor
import dit4c.scheduler.runner.CommandExecutorHelper
import org.apache.sshd.common.keyprovider.MappedKeyPairProvider
import java.security.spec.ECGenParameterSpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import scala.concurrent.Future

class RemoteShellSpec(implicit ee: ExecutionEnv) extends Specification
    with ScalaCheck with FileMatchers {

  implicit val params = Parameters(minTestsOk = 1000, workers = 20)

  val keyPairs: Seq[KeyPair] = generateRsaKeyPairs(5)
  def publicKeys = keyPairs.map(_.getPublic)

  object withCommandExecutor extends Fixture[CommandExecutor] {
    override def apply[R: AsResult](f: CommandExecutor => R) = {
      import dit4c.common.KeyHelpers._
      val kp = Random.shuffle(keyPairs).head
      val username = Random.alphanumeric.take(8).mkString
      val ce: CommandExecutor = RemoteShell(server.getHost,
        server.getPort,
        username,
        Future.successful(
          RemoteShell.OpenSshKeyPair(
              kp.getPrivate.asInstanceOf[RSAPrivateKey].pkcs8.pem,
              kp.getPublic.asInstanceOf[RSAPublicKey].ssh.authorizedKeys) :: Nil),
        Future.successful(
          hostPublicKey.asInstanceOf[RSAPublicKey]))
      AsResult(f(ce))
    }
  }

  "RemoteShell" >> {

    "CommandExecutor" >> {
      "can handle single commands" >> withCommandExecutor { ce: CommandExecutor =>
        ce(Seq("whoami")).map(_.trim) must {
          be_==(System.getProperty("user.name"))
        }.awaitFor(1.minute)
      }

      "can handle commands with arguments" >> withCommandExecutor { ce: CommandExecutor =>
        // To do tests parallel, action must happen in the generator
        case class TestPair(input: String, output: String)
        implicit val testArb: Arbitrary[TestPair] = Arbitrary {
          for {
            input <- Gen.oneOf(Arbitrary.arbString.arbitrary, Gen.alphaStr)
            output = Await.result(ce(Seq("echo","-n", input)), 1.minute)
          } yield TestPair(input, output)
        }
        // Check the input and output
        prop({ p: TestPair =>
          p.output must_==(withoutControlCharacters(p.input))
        })
      }

      "can create files" >> withCommandExecutor { ce: CommandExecutor =>
        implicit val ec = ExecutionContext.fromExecutorService(
          Executors.newCachedThreadPool())
        val tmpDir = Files.createTempDirectory("remote-shell-test-")
        tmpDir.toFile.deleteOnExit
        // To do tests parallel, action must happen in the generator
        case class TestPair(file: Path, content: Array[Byte])
        implicit val testArb: Arbitrary[TestPair] = Arbitrary {
          for {
            bytes <- Arbitrary.arbitrary[Array[Byte]]
            randomId <- Gen.containerOfN(20, Gen.alphaNumChar).map(_.mkString)
            tmpFile = tmpDir.resolve("test-"+randomId).toAbsolutePath
            _ = Await.result(ce(
              Seq("dd", s"of=${tmpFile}"),
              new ByteArrayInputStream(bytes)), 1.minute)
          } yield TestPair(tmpFile, bytes)
        }
        // Do the checks
        prop({ p: TestPair =>
          try {
            { p.file.toString must beAFilePath } and
            { readFileBytes(p.file) must_== p.content }
          } finally {
            Files.deleteIfExists(p.file)
          }
        }).set(maxSize = 1024)
      }

      "exits with non-zero on error" >> withCommandExecutor { ce: CommandExecutor =>
        ce(Seq("doesnotexist")) must {
          throwAn[Exception].like {
            case e => e.getMessage must contain("not found")
          }
        }.awaitFor(1.minute)
      }
    }

    "can fetch host keys" >> {
      RemoteShell.getHostKey(server.getHost, server.getPort) must {
        be_==(hostPublicKey)
      }.awaitFor(1.minute)
    }

  }

  val (server, hostPublicKey): (SshServer, PublicKey) = {
    val server = SshServer.setUpDefaultServer()
    server.setHost("127.42.7.5") // On loopback, but not localhost
    server.setPort(0) // Random port
    val (keyPairProvider, publicKey) = generateKeyPairProvider
    server.setKeyPairProvider(keyPairProvider)
    server.setPublickeyAuthenticator(
        new KeySetPublickeyAuthenticator(publicKeys))
    server.setCommandFactory(new CommandFactory() {
      // Generic command factory that just passes the command to a shell
      def createCommand(command: String) =
        new InvertedShellWrapper(new ProcessShell(command))
    })
    server.start()
    (server, publicKey)
  }

  def generateKeyPairProvider: (KeyPairProvider, PublicKey) = {
    val sr = SecureRandom.getInstance("SHA1PRNG")
    // We understand RSA key pairs
    val rsaPair = {
      val kpg = KeyPairGenerator.getInstance("RSA")
      kpg.initialize(2048, sr)
      kpg.genKeyPair
    }
    // We want a pair that we don't understand too
    val ecdsaPair = {
      val ecGenSpec = new ECGenParameterSpec("P-256");
      val kpg = KeyPairGenerator.getInstance("EC", new BouncyCastleProvider())
      kpg.initialize(ecGenSpec, sr)
      kpg.genKeyPair
    }
    (MappedKeyPairProvider.MAP_TO_KEY_PAIR_PROVIDER.transform(Map(
      KeyPairProvider.SSH_RSA -> rsaPair,
      KeyPairProvider.ECDSA_SHA2_NISTP256 -> ecdsaPair
    )), rsaPair.getPublic)
  }

  def generateRsaKeyPairs(n: Int): Seq[KeyPair] = {
    val sr = SecureRandom.getInstance("SHA1PRNG")
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(512, sr)
    Seq.fill(n) { kpg.genKeyPair }
  }

  private def withoutControlCharacters(s: String): String =
    s.filterNot(c => { c <= '\u001f' || c == '\u001f'})

  def readFileBytes(file: Path): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    BasicIO.transferFully(new FileInputStream(file.toFile), out)
    out.toByteArray
  }

}