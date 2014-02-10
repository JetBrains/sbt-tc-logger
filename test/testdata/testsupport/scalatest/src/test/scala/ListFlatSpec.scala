/*
 * Copyright 2013-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0.
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */

import org.scalatest.{FlatSpec, ShouldMatchers, FunSpec}

class ListFlatSpec extends FlatSpec with ShouldMatchers {
  val xs = List(1, 2, 3)

  "A List" should "have length as count of elements in it" in {
    xs.length should be (3)
  }

  it should "contains elements passed in the factory method" in {
    xs.contains(1) should be (false)
    xs.contains(2) should be (true)
    xs.contains(3) should be (true)
  }

  it should "throw IndexOutOfBounds exception when index is out of bounds" in {
    evaluating {
      xs(4)
    } should produce [IndexOutOfBoundsException]
  }
}