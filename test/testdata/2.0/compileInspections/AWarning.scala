object WarnMe {
  sealed trait Thing
  case object A extends Thing
  case object B extends Thing
  case object C extends Thing

  def printThing(t: Thing) = t match {
    case A => println("Ah!")
    case B => println("Bee!")
    // C is missing: should give warning "match may not be exhaustive"
  }

  ("something": Any) match {
    case impossibly: Int =>
    case Some(silly: Int) =>
      silly
    case anything =>
    case unreached =>
  }
}