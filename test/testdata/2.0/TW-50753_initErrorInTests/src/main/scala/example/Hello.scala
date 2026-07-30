package example

object Hello {
  val greeting: String = "hello"

  val thisShouldFail: String = trickCompiler(true)

  def trickCompiler(tricked: Boolean): String = {
    if (tricked) {
      throw new IllegalArgumentException("Test TC sbt logger plugin")
    } else {
      "never happens"
    }
  }
}
