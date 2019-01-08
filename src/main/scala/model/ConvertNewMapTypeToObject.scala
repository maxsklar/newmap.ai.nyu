package ai.newmap.model

object ConvertNewMapTypeToObject {
  def apply(newMapType: NewMapType): NewMapObject = newMapType match {
    case IndexT(i: Long) => Index(i)
    case CountT => CountType
    case IdentifierT => IdentifierType
    case MapT(key, value, default) => MapType(this(key), this(value), default)
    case StructT(params: Vector[(String, NewMapType)]) => paramsToObject(params, Index(1))
    case CaseT(params: Vector[(String, NewMapType)]) => paramsToObject(params, Index(0))
    case LambdaT(inputType, outputType) => {
      LambdaType(this(inputType), this(outputType))
    }
    case SubstitutableT(s: String) => ParameterObj(s)
    case TypeT => TypeType
    case Subtype(t: NewMapType) => SubtypeType(this(t))
    case SubtypeFromMapType(m: MapInstance) => SubtypeFromMap(m)
    /*case MutableT(staticType, init, commandType, updateFunction) => {
      MutableType(this(staticType), init, this(commandType), updateFunction)
    }*/
    case IncrementT(baseType) => IncrementType(this(baseType))
  }

  // TODO(max): These 2 methods should not be needed
  // Vector[(String, NewMapObject)] should not be stored, only type. We'll have to fix this somehow
  def paramsToObjectParams(params: Vector[(String, NewMapType)]): Vector[(String, NewMapObject)] = {
    params.map(param => (param._1 -> ConvertNewMapTypeToObject(param._2)))
  }

  def paramsToObject(
    params: Vector[(String, NewMapType)],
    default: NewMapObject
  ): MapInstance = {
    val paramsAsObjects: Vector[(NewMapObject, NewMapObject)] = for {
      (name, nmt) <- params
    } yield {
      IdentifierInstance(name) -> ConvertNewMapTypeToObject(nmt)
    }
    //// TODO
    MapInstance(paramsAsObjects, default)
  }
}