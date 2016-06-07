package dit4c.scheduler.ssh

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.util.Random

import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.CommandFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.KeySetPublickeyAuthenticator
import org.apache.sshd.server.shell.InvertedShellWrapper
import org.apache.sshd.server.shell.ProcessShell
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification

import dit4c.scheduler.runner.CommandExecutor
import dit4c.scheduler.runner.CommandExecutorHelper
import org.specs2.execute.AsResult
import org.specs2.specification.ForEach
import java.nio.file.Files
import scala.concurrent.Await
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.specs2.matcher.FileMatchers
import java.io.FileInputStream
import scala.sys.process.BasicIO
import org.specs2.ScalaCheck
import org.scalacheck.Gen
import java.security.SecureRandom
import org.specs2.scalacheck.Parameters
import org.scalacheck.Arbitrary
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors

class RemoteShellSpec(implicit ee: ExecutionEnv) extends Specification
    with ForEach[CommandExecutor] with ScalaCheck with FileMatchers {

  implicit val params = Parameters(minTestsOk = 1000, workers = 20)

  val keyPairs: Seq[KeyPair] = generateRsaKeyPairs(5)
  def publicKeys = keyPairs.map(_.getPublic)

  override def foreach[R: AsResult](f: CommandExecutor => R) = {
    val kp = Random.shuffle(keyPairs).head
    val username = Random.alphanumeric.take(8).mkString
    val ce: CommandExecutor = RemoteShell(server.getHost,
      server.getPort,
      username: String,
      kp.getPrivate.asInstanceOf[RSAPrivateKey],
      kp.getPublic.asInstanceOf[RSAPublicKey],
      hostPublicKey.asInstanceOf[RSAPublicKey])
    AsResult(f(ce))
  }

  "RemoteShell" >> {
    "can handle single commands" >> { ce: CommandExecutor =>
      ce(Seq("whoami")).map(_.trim) must {
        be_==(System.getProperty("user.name"))
      }.awaitFor(1.minute)
    }

    "can handle commands with arguments" >> { ce: CommandExecutor =>
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

    "can create files" >> { ce: CommandExecutor =>
      implicit val ec = ExecutionContext.fromExecutorService(
        Executors.newCachedThreadPool())
      val tmpDir = Files.createTempDirectory("remote-shell-test-")
      tmpDir.toFile.deleteOnExit
      // To do tests parallel, action must happen in the generator
      case class TestPair(file: Path, content: Array[Byte], bytesWritten: Int)
      implicit val testArb: Arbitrary[TestPair] = Arbitrary {
        for {
          bytes <- Arbitrary.arbitrary[Array[Byte]]
          randomId <- Gen.containerOfN(20, Gen.alphaNumChar).map(_.mkString)
          tmpFile = tmpDir.resolve("test-"+randomId).toAbsolutePath
          numBytesWritten = Await.result(ce(
            Seq("sh", "-c", s"cat - > ${tmpFile} && wc -c ${tmpFile} | cut -d' ' -f1"),
            new ByteArrayInputStream(bytes)).map(_.trim.toInt), 1.minute)
        } yield TestPair(tmpFile, bytes, numBytesWritten)
      }
      // Do the checks
      prop({ p: TestPair =>
        try {
          { p.file.toString must beAFilePath } and
          { p.bytesWritten must_== p.content.length } and
          { readFileBytes(p.file) must_== p.content }
        } finally {
          Files.deleteIfExists(p.file)
        }
      }).set(maxSize = 1024)
    }

    "exits with non-zero on error" >> { ce: CommandExecutor =>
      ce(Seq("doesnotexist")) must {
        throwAn[Exception].like {
          case e => e.getMessage must contain("not found")
        }
      }.awaitFor(1.minute)
    }

  }

  val (server, hostPublicKey): (SshServer, PublicKey) = {
    val server = SshServer.setUpDefaultServer()
    server.setHost("localhost")
    val (keyPairProvider, publicKey) = {
      val pair = generateRsaKeyPairs(1).head
      (new KeyPairProvider() {
        private val keyType = KeyPairProvider.SSH_RSA
        override val getKeyTypes = asJavaIterable(Seq(keyType))
        override def loadKey(t: String) =
          if (t == keyType) pair else null
        override val loadKeys = asJavaIterable(Seq(pair))
      }, pair.getPublic)
    }
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