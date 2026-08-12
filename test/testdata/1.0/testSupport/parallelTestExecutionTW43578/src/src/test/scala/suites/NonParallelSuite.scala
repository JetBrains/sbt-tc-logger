package suites

import org.scalatest.Suites
import tests.{NonParallelTest, ParallelTest}

class NonParallelSuite extends Suites(new NonParallelTest, new ParallelTest) {

}