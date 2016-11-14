package dit4c.common

import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.scalacheck.Gen
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import org.specs2.specification.AllExpectations
import org.scalacheck.Arbitrary
import org.specs2.scalacheck.Parameters
import org.specs2.matcher.Matcher
import java.io.ByteArrayInputStream
import org.bouncycastle.openpgp._
import org.bouncycastle.openpgp.operator.jcajce._
import org.bouncycastle.openpgp.operator.bc._
import org.bouncycastle.bcpg._
import java.util.Date
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import scala.sys.process.ProcessIO
import java.io.InputStream

class KeyHelpersSpec extends Specification with ScalaCheck with AllExpectations {

  implicit val params = Parameters(minTestsOk = 20)

  case class KeyBits(n: Int)

  "KeyHelpers" should {
    import KeyHelpers._

    // We never want an empty string for these checks
    implicit val arbKeyBits: Arbitrary[KeyBits] = Arbitrary(Gen.frequency(10 -> 1024, 1 -> 2048).map(KeyBits.apply))
    val genNonEmptyString: Gen[String] =
      Gen.oneOf(Gen.alphaStr, Arbitrary.arbString.arbitrary)
        .suchThat(!_.isEmpty)
    implicit val arbString = Arbitrary(genNonEmptyString)

    "produce OpenPGP armoured secret keys" >> prop({ (identity: String, bits: KeyBits, passphrase: Option[String]) =>
      val pgpKey = KeyHelpers.PGPKeyGenerators.RSA(identity, bits.n, passphrase)
      val outputKey = pgpKey.`private`.armoured
      val outputKeyStr = new String(outputKey, "utf8")
      val lines = outputKeyStr.lines.toSeq;
      {
        lines must
          haveFirstLine("-----BEGIN PGP PRIVATE KEY BLOCK-----") and
          haveLastLine("-----END PGP PRIVATE KEY BLOCK-----")
      } and {
        import scala.collection.JavaConversions._
        val secretKeyCollection = new PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(new ByteArrayInputStream(outputKey)), new JcaKeyFingerprintCalculator())
        secretKeyCollection.getKeyRings.next.getSecretKey must beLike { case sk =>
          val privateKey = sk.extractPrivateKey(passphrase match {
            case None => null
            case Some(passphrase) =>
              new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider()).build(passphrase.toCharArray)
          })
          (sk.getKeyID must be_==(pgpKey.getKeyID)) and
          (sk.getUserIDs.next must be_==(identity)) and
          (sk.getPublicKey must beLike({ case pubKey =>
            val desiredFlags = {
              import org.bouncycastle.bcpg.sig.KeyFlags._
              AUTHENTICATION|ENCRYPT_COMMS|ENCRYPT_STORAGE|SIGN_DATA
            }
            val sig = pubKey.getSignaturesForKeyID(pubKey.getKeyID).toList.head
            (sig.getCreationTime must beLessThan(new Date())) and
            // If at least required bits are set, then bit-wise AND of desiredFlags should be desiredFlags
            ((sig.getHashedSubPackets.getKeyFlags & desiredFlags) must_==(desiredFlags))
          }))
        }
      }
    })

    "parse armored public keys" >> prop({ (identity: String, bits: KeyBits) =>
      val pgpKey = KeyHelpers.PGPKeyGenerators.RSA(identity, bits.n, None)
      val outputKey = pgpKey.`public`.armoured
      val outputKeyStr = new String(outputKey, "utf8")
      parseArmoredPublicKey(outputKeyStr) must beRight(beLike[PGPPublicKey] {
        case parsedKey =>
          parsedKey.getFingerprint must_==(pgpKey.getPublicKey.getFingerprint)
      })
    })

    "produce PCKS#1 keys from PGP keys" >> prop({ (identity: String, bits: KeyBits) =>
      import sys.process._
      val pgpKey = KeyHelpers.PGPKeyGenerators.RSA(identity, bits.n, None)
      val is = new ByteArrayInputStream(pgpKey.asRSAPrivateKey().pkcs1.pem.getBytes)
      val os = new ByteArrayOutputStream()
      def sendToOs(in: InputStream) = Iterator.continually(in.read).takeWhile(_>=0).foreach(os.write)
      val processIO = new ProcessIO(_ => (), sendToOs, sendToOs, true)
      "openssl rsa -check".#<(is).run(processIO).exitValue must beLike {
        case 0 => ok
        case other =>
          val output = new String(os.toByteArray())
          ko(s"OpenSSL check of key failed\n$output")
      }
    })

  }

  def haveFirstLine(s: String) = (be_==(s)) ^^ { (xs: Seq[String]) => xs.head }
  def haveLastLine(s: String) = (be_==(s)) ^^ { (xs: Seq[String]) => xs.last }


}