package korolev

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ClientAssetSpec extends AnyFlatSpec with Matchers {
  "Korolev client asset" should "exist on the classpath" in {
    val stream = Option(getClass.getResourceAsStream("/static/korolev-client.min.js"))
    try {
      stream.isDefined shouldBe true
    } finally {
      stream.foreach(_.close())
    }
  }

  it should "include the source map on the classpath" in {
    val stream = Option(getClass.getResourceAsStream("/static/korolev-client.min.js.map"))
    try {
      stream.isDefined shouldBe true
    } finally {
      stream.foreach(_.close())
    }
  }
}
