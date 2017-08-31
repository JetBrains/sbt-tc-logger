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

import org.scalatest.junit.AssertionsForJUnit
import org.junit.Assert._
import org.junit._

class PointTest extends AssertionsForJUnit  {

  @Test
  def verifySomethingTest() {
     val point = new Point(1,1)
     point.move(2,2)
     assert(3 === point.x)
     assert(3 === point.y)
  }


}

