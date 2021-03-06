package dit4c.scheduler.ssh

import java.io.InputStream
import java.io.OutputStream
import java.io.StringWriter
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Random

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session

import dit4c.scheduler.runner.CommandExecutor
import java.io.SequenceInputStream
import java.io.ByteArrayInputStream
import java.security.PublicKey
import akka.util.ByteString
import java.security.spec.RSAPublicKeySpec
import java.security.KeyFactory
import scala.concurrent.Promise
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSchException

object RemoteShell {

  /**
   * @param private    PEM-encoded PKCS#8 private key
   * @param public     OpenSSH public key
   */
  case class OpenSshKeyPair(`private`: String, `public`: String)

  implicit val executionContext = ExecutionContext.fromExecutorService(
      Executors.newCachedThreadPool())

  def apply(
      host: String,
      port: Int,
      username: String,
      fetchUserKeyPairs: => Future[List[OpenSshKeyPair]],
      fetchHostPublicKey: => Future[RSAPublicKey]): CommandExecutor = {
    import dit4c.common.KeyHelpers._
    var lastSession: Option[Session] = None
    ce {
      if (lastSession.isDefined && lastSession.get.isConnected)
        Future.successful(lastSession.get)
      else
        for {
          userKeyPairs <- fetchUserKeyPairs
          hostPublicKey <- fetchHostPublicKey
        } yield {
          val jsch = new JSch
          userKeyPairs.foreach { kp =>
            jsch.addIdentity("id",
                kp.`private`.getBytes,
                kp.`public`.getBytes,
                null)
          }
          jsch.getHostKeyRepository.add(
              new HostKey(host, hostPublicKey.ssh.raw),
              null);
          val session = jsch.getSession(username, host, port)
          // Keep the session alive
          session.setServerAliveInterval(5000)
          // Connect with one second timeout
          session.connect(1000)
          lastSession = Some(session)
          session
        }
    }
  }

  def getHostKey(host: String, port: Int): Future[RSAPublicKey] = {
    val jsch = new JSch
    val p = Promise[RSAPublicKey]()
    jsch.setHostKeyRepository(new HostKeyRepository {
      def add(x$1: com.jcraft.jsch.HostKey,x$2: com.jcraft.jsch.UserInfo): Unit = ???
      def check(x$1: String, key: Array[Byte]): Int = {
        RemoteShell.fromOpenSshPublicKey(key) match {
          case k: RSAPublicKey => p.trySuccess(k)
        }
        HostKeyRepository.NOT_INCLUDED
      }
      def getHostKey(x$1: String,x$2: String): Array[com.jcraft.jsch.HostKey] = ???
      def getHostKey(): Array[com.jcraft.jsch.HostKey] = ???
      def getKnownHostsRepositoryID(): String = ???
      def remove(x$1: String,x$2: String,x$3: Array[Byte]): Unit = ???
      def remove(x$1: String,x$2: String): Unit = ???
    })
    (new Thread() {
      override def run {
        try {
          val username = ""
          // Connect with one second timeout
          jsch.getSession(username, host, port).connect(1000)
        } catch {
          case e: JSchException =>
            // This should emit Failure, but only when we didn't get the keys
            p.tryFailure(e)
        }
      }
    }).start
    p.future
  }

  protected def ce(sessionProvider: => Future[Session]): CommandExecutor =
    (cmd: Seq[String], in: InputStream, out: OutputStream, err: OutputStream) =>
      sessionProvider.map { session =>
        val channel: ChannelExec =
          session.openChannel("exec").asInstanceOf[ChannelExec]
        val cmdLine = escapeAndJoin(cmd)
        channel.setCommand("bash")
        channel.setInputStream(
          new SequenceInputStream(
            new ByteArrayInputStream(("exec "+cmdLine+"\n").getBytes),
            in))
        channel.setOutputStream(out)
        channel.setErrStream(err)
        channel.connect() // Default timeout → 1000 x 50ms
        while (channel.isConnected) {
          Thread.sleep(10)
        }
        channel.getExitStatus
      }

  protected def escapeAndJoin(cmd: Seq[String]): String =
    cmd.map(_.flatMap(escape)).mkString(" ")

  /**
   * Escape normal characters and strip out control characters.
   */
  protected def escape(c: Char): Seq[Char] = c match {
    case c if c <= '\u001f' || c == '\u001f' => Seq.empty
    case c => Seq('\\', c)
  }

  def fromOpenSshPublicKey(bytes: Array[Byte]): PublicKey = {
    import java.nio.ByteBuffer
    def readLengthAndSplit(bs: Array[Byte]): (Array[Byte], Array[Byte]) = {
      val start = Integer.BYTES
      val length = BigInt(bs.take(Integer.BYTES)).toInt
      val end = start + length
      (bs.slice(start, end), bs.drop(end))
    }
    def extractType(bs: Array[Byte]): (String, Array[Byte]) = {
      val (v, remaining) = readLengthAndSplit(bs)
      (new String(v, "us-ascii"), remaining)
    }
    val (algType, keyBytes) = extractType(bytes)
    algType match {
      case "ssh-rsa" =>
        val (publicExponentBytes, remaining) = readLengthAndSplit(keyBytes)
        val (modulusBytes, _) = readLengthAndSplit(remaining)
        val factory = KeyFactory.getInstance("RSA")
        factory.generatePublic(
          new RSAPublicKeySpec(
              BigInt(modulusBytes).bigInteger,
              BigInt(publicExponentBytes).bigInteger))
      case _ => ???
    }
  }


}