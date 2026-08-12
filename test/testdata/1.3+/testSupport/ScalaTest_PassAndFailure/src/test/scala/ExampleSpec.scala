

import scala.collection.mutable
import org.scalatest.funspec.AnyFunSpec

class ExampleSpec extends AnyFunSpec {

  describe("A Stack") {

    it("should pop values in last-in-first-out order") {
      val stack = mutable.Stack.empty[Int]
      stack.push(1)
      stack.push(2)
      assert(stack.pop() === 2)
      assert(stack.pop() === 1)
    }

    it("should throw NoSuchElementException if an empty stack is popped") {
      val emptyStack = mutable.Stack.empty[Int]
      intercept[NoSuchElementException] {
        emptyStack.pop()
      }
    }
  }
}
