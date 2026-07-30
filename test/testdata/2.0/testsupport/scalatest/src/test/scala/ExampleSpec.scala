

import collection.mutable.Stack
import org.scalatest._
import scala.collection.mutable.Stack.StackBuilder
import org.scalatest.FunSpec

class ExampleSpec extends FunSpec {

  describe("A Stack") {

    it("should pop values in last-in-first-out order") {
      val stack = new StackBuilder[Int].result()
      stack.push(1)
      stack.push(2)
      assert(stack.pop() === 2)
      assert(stack.pop() === 1)
    }

    it("should throw NoSuchElementException if an empty stack is popped") {
      val emptyStack = new StackBuilder[Int].result()
      intercept[NoSuchElementException] {
        emptyStack.pop()
      }
    }
  }
}