

package org.jetbrains.teamcity

import org.specs2.mutable._

class ExampleSpec extends Specification {
  "This is a specification for the 'Hello world' string".txt

  "The 'Hello world' string should" >> {
    "contain 11 characters" >> {
      "Hello world" must haveSize(11)
    }
    "start with 'Hello'" >> {
      "Hello world" must startWith("Hello")
    }
    "end with 'world'" >> {
      "Hello world" must endWith("world")
    }
  }
}