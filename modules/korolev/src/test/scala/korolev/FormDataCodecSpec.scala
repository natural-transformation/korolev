package korolev

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import korolev.server.internal.FormDataCodec
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FormDataCodecSpec extends AnyFlatSpec with Matchers {
  "decode" should "parse valid multipart/form-data body" in {
    val body = """--Asrf456BGe4h
                 |Content-Disposition: form-data; name="DestAddress"
                 |
                 |brutal-vasya@example.com
                 |--Asrf456BGe4h
                 |Content-Disposition: form-data; name="MessageTitle"
                 |
                 |I'm indignant
                 |--Asrf456BGe4h
                 |Content-Disposition: form-data; name="MessageText"
                 |
                 |Hello, Vasily! Your hand lion, which you left with me
                 |last week, tore my whole sofa. Please take it away
                 |soon! In the attachment, two pictures with consequences.
                 |--Asrf456BGe4h
                 |Content-Disposition: form-data; name="AttachedFile1"; filename="horror-photo-1.jpg"
                 |Content-Type: image/jpeg
                 |
                 |<blob1>
                 |--Asrf456BGe4h
                 |Content-Disposition: form-data; name="AttachedFile2"; filename="horror-photo-2.jpg"
                 |Content-Type: image/jpeg
                 |
                 |<blob2>
                 |--Asrf456BGe4h--
    """.stripMargin

    val bodyBuffer = ByteBuffer.wrap(body.getBytes(StandardCharsets.US_ASCII))
    val codec      = new FormDataCodec()
    val formData   = codec.decode(bodyBuffer, "Asrf456BGe4h")

    formData.text("DestAddress") should be("brutal-vasya@example.com")
    formData.text("MessageTitle") should be("I'm indignant")
    formData.bytes("AttachedFile2") should be {
      ByteBuffer.wrap("<blob2>".getBytes)
    }
  }

  "decode" should "parse empty multipart/form-data body" in {
    val body = """------WebKitFormBoundaryrBVqcOqR4KNX8jT9--\r\n
    """.stripMargin

    val bodyBuffer = ByteBuffer.wrap(body.getBytes(StandardCharsets.US_ASCII))
    val codec      = new FormDataCodec()
    val formData   = codec.decode(bodyBuffer, "----WebKitFormBoundaryVLDwcP1YkcvPtjGM")

    formData.bytesOpt("any") should be(None)
  }
}
