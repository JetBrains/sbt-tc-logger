

import math._

class Line(xc: Point, yc: Point) {
  var x: Point = xc
  var y: Point = yc

  def length() {
    sqrt((x.x-y.x)*(x.x-y.x)+(x.y-y.y)*(x.y-y.y))
  }

}