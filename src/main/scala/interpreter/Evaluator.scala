package ai.newmap.interpreter

import ai.newmap.model._
import ai.newmap.util.{Outcome, Success, Failure}

// Evaluates an expression that's already been type checked
object Evaluator {
  def apply(
    nObject: NewMapObject,
    env: Environment
  ): Outcome[NewMapObject, String] = {
    nObject match {
      case Index(_) | TypeType | IdentifierType | IdentifierInstance(_) | ParameterObj(_) => {
        Success(nObject)
      }
      case MapType(key, value, default) => {
        for {
          evalKey <- this(key, env)
          evalValue <- this(value, env)
          evalDefault <- this(default, env)
        } yield {
          MapType(evalKey, evalValue, evalDefault)
        }
      }
      case MapInstance(values: Vector[(NewMapObject, NewMapObject)], default) => {
        for {
          evalDefault <- this(default, env)
          evalValues <- evalMapInstanceVals(values, env)
        } yield MapInstance(evalValues, evalDefault)
      }
      case LambdaInstance(params: Vector[(String, NewMapObject)], expression) => {
        for {
          evalExpression <- this(expression, env)
          evalParams <- evalParameters(params, env)
        } yield {
          LambdaInstance(evalParams, evalExpression)
        }
      }
      case ApplyFunction(func, input) => {
        for {
          evalInput <- this(input, env)
          evalFunc <- this(func, env)
          result <- applyFunctionAttempt(evalFunc, evalInput, env)
        } yield result
      }
      case StructType(params) => {
        for {
          evalParams <- this(params, env)
        } yield StructType(evalParams)
      }
      case CaseType(params) => {
        for {
          evalParams <- this(params, env)
        } yield CaseType(evalParams)
      }
      case StructInstance(value: Vector[(String, NewMapObject)]) => {
        for {
          evalValue <- evalParameters(value, env)
        } yield StructInstance(evalValue)
      }
      case CaseInstance(constructor: String, input: NewMapObject) => {
        for {
          evalInput <- this(input, env)
        } yield CaseInstance(constructor, evalInput)
      }
      case SubtypeType(parentType) => {
        for {
          evalParentType <- this(parentType, env)
        } yield SubtypeType(evalParentType)
      }
      case SubtypeFromMap(map: MapInstance) => {
        for {
          evalMapValues <- evalMapInstanceVals(map.values, env)
          evalMapDefault <- this(map.default, env)
        } yield SubtypeFromMap(MapInstance(evalMapValues, evalMapDefault))
      }
    }
  }

  def evalMapInstanceVals(
    values: Vector[(NewMapObject, NewMapObject)],
    env: Environment
  ): Outcome[Vector[(NewMapObject, NewMapObject)], String] = values match {
    case (k, v) +: restOfValues => {
      for {
        evalK <- this(k, env)
        evalV <- this(v, env)
        evalRest <- evalMapInstanceVals(restOfValues, env)
      } yield {
        (evalK -> evalV) +: evalRest
      }
    }
    case _ => Success(Vector.empty)
  }

  def evalParameters(
    params: Vector[(String, NewMapObject)],
    env: Environment
  ): Outcome[Vector[(String, NewMapObject)], String] = params match {
    case (k, v) +: restOfValues => {
      for {
        evalV <- this(v, env)
        evalRest <- evalParameters(restOfValues, env)
      } yield {
        (k -> evalV) +: evalRest
      }
    }
    case _ => Success(Vector.empty)
  }

  def applyFunctionAttempt(
    func: NewMapObject,
    input: NewMapObject,
    env: Environment
  ): Outcome[NewMapObject, String] = {
    (func, input) match {
      case (LambdaInstance(params, expression), StructInstance(paramValues)) => {
        for {
          newEnv <- updateEnvironmentWithParamValues(params, paramValues, env)
          substitutedExpression = makeRelevantSubsitutions(expression, newEnv)
          result <- this(substitutedExpression, env)
        } yield result
      }
      case (LambdaInstance(params, expression), firstParamValue) => {
        // Here we are passing in the first parameter to the function

        // TODO - this isn't type safe until we enforce the at-least-one-param-rule
        val firstParam = params.head

        for {
          newEnv <- updateEnvironmentWithParamValues(Vector(firstParam), Vector(firstParam._1 -> firstParamValue), env)
          substitutedExpression = makeRelevantSubsitutions(expression, newEnv)

          result <- this(substitutedExpression, env)
        } yield {
          if (params.length == 1) result else {
            val newParams = params.drop(1).map(param => {
              // TODO: resolveType will only make the subsitution if the type is directly a SubstitutableT
              // We need to figure out a "Let" statement (where to put extra env commands) for the more complex results
              param._1 -> makeRelevantSubsitutions(
                param._2,
                env.newCommand(FullEnvironmentCommand(params(0)._1, TypeT, params(0)._2))
              )
            })

            LambdaInstance(newParams, result)
          }
        }
      }
      case (MapInstance(values, default), key) => {
        for {
          evaluatedKey <- this(key, env)
        } yield {
          evaluatedKey match {
            case ParameterObj(s) => {
              // The function can't be applied because we don't have the input yet
              ApplyFunction(func, input)
            }
            case _ => {
              values.find(_._1 == evaluatedKey).map(_._2) match {
                case Some(result) => result
                case None => default
              }
            }
          }
        }
      }
      case (StructInstance(value: Vector[(String, NewMapObject)]), identifier) => {
        val id = makeRelevantSubsitutions(identifier, env)
        Success(value.find(x => IdentifierInstance(x._1) == id).map(_._2).getOrElse(Index(0)))
      }
      case _ => {
        Failure("Not implemented: apply function when not lambdainstance and structinstance\nCallable: " + func + "\nInput:" + input)
      }
    }
  }

  def updateEnvironmentWithParamValues(
    paramTypes: Vector[(String, NewMapObject)],
    paramValues: Vector[(String, NewMapObject)],
    env: Environment
  ): Outcome[Environment, String] = {
    (paramTypes, paramValues) match {
      case ((firstParamType +: addlParamTypes), (firstParamValue +: addlParamValues)) => {
        for {
          _ <- Outcome.failWhen(
            firstParamType._1 != firstParamValue._1,
            "Params don't agree: " + firstParamType._1 + " vs " + firstParamValue._1
          )
          typeInformation <- convertObjectToType(firstParamType._2, env)

          envCommand = FullEnvironmentCommand(
            firstParamType._1,
            typeInformation,
            firstParamValue._2
          )
          newEnv = env.newCommand(envCommand)

          result <- updateEnvironmentWithParamValues(addlParamTypes, addlParamValues, newEnv)
        } yield result
      }
      // TODO - what if one is longer than the other
      case _ => Success(env)
    }
  }

  def makeRelevantSubsitutions(
    expression: NewMapObject,
    env: Environment
  ): NewMapObject = {
    expression match {
      case Index(_) | TypeType | IdentifierType | IdentifierInstance(_) => expression
      case MapType(key, value, default) => {
        MapType(makeRelevantSubsitutions(key, env), makeRelevantSubsitutions(value, env), makeRelevantSubsitutions(default, env))
      }
      case MapInstance(values, default) => {
        val newValues = for {
          (k, v) <- values
        } yield (makeRelevantSubsitutions(k, env) -> makeRelevantSubsitutions(v, env))

        MapInstance(newValues, default)
      }
      case LambdaInstance(params, expression) => {
        val newEnv = includeParams(params, env)
        val newExpression = makeRelevantSubsitutions(expression, newEnv)
        LambdaInstance(params, newExpression)
      }
      case ApplyFunction(func, input) => {
        ApplyFunction(
          makeRelevantSubsitutions(func, env),
          makeRelevantSubsitutions(input, env)
        )
      }
      case ParameterObj(name) => {
        env.objectOf(name) match {
          case Some(obj) => obj
          case None => expression
        }
      }
      case StructType(values) => {
        StructType(makeRelevantSubsitutions(values, env))
      }
      case CaseType(values) => {
        CaseType(makeRelevantSubsitutions(values, env))
      }
      case StructInstance(value) => {
        StructInstance(value.map(x => (x._1 -> makeRelevantSubsitutions(x._2, env))))
      }
      case CaseInstance(constructor, value) => {
        CaseInstance(constructor, makeRelevantSubsitutions(value, env))      
      }
      case SubtypeType(parentType) => {
        SubtypeType(makeRelevantSubsitutions(parentType, env))
      }
      case SubtypeFromMap(MapInstance(values, default)) => {
        val newValues = for {
          (k, v) <- values
        } yield (makeRelevantSubsitutions(k, env) -> makeRelevantSubsitutions(v, env))

        val newMapInstance = MapInstance(newValues, default)

        SubtypeFromMap(newMapInstance)
      }
    }
  }

  def includeParams(
    params: Vector[(String, NewMapObject)],
    env: Environment
  ): Environment = {
    params match {
      case (paramName, paramObj) +: addlParams => {
        // TODO: fix unsafe object to type conversion
        // - This happens when we merge the object and type representations
        val nType = convertObjectToType(paramObj, env).toOption.get
        val newEnv = env.newParam(paramName, nType)
        includeParams(addlParams, newEnv)
      }
      case _ => env
    }
  }

    // Already assume the object is a type
  // TODO - once objects and type are unified, this should become unneccesary
  def convertObjectToType(
    objectFound: NewMapObject,
    env: Environment
  ): Outcome[NewMapType, String] = {
    objectFound match {
      case Index(i) => Success(IndexT(i))
      case TypeType => Success(TypeT)
      case IdentifierType => Success(IdentifierT)
      case MapType(key, value, default) => {
        for {
          keyType <- convertObjectToType(key, env) 
          valueType <- convertObjectToType(value, env)
        } yield {
          MapT(keyType, valueType, default)
        }
      }
      case LambdaInstance(params, result) => {
        // TODO: remove unsafe call to objectParamsToParams
        val newParams = TypeChecker.objectParamsToParams(params, env)
        for {
          resultType <- convertObjectToType(result, env.newParams(newParams))
        } yield {
          LambdaT(newParams, resultType)
        }
      }
      case MapInstance(values, Index(1)) => {
        // TODO - require an explicit conversion here? Maybe this should be left to struct type
        for {
          // This must be a identifier -> type map
          newParams <- convertMapInstanceStructToParams(values, env)
        } yield {
          StructT(newParams)
        }
      }
      case ParameterObj(name) => {
        for {
          typeInfoOfObjectFound <- env.typeOf(name)

          typeOfObjectFound <- typeInfoOfObjectFound match {
            case ExplicitlyTyped(nType) => Success(nType)
            case ImplicitlyTyped(types) => {
              Failure("Param Obj not implemented for ImplicitlyTyped case")
            }
          }

          _ <- Outcome.failWhen(
            !TypeChecker.refersToAType(typeOfObjectFound, env),
            "Could not confirm " + name + " as a type. The elements of type " + typeOfObjectFound.toString + " are not generally types themselves."
          )
        } yield {
          SubstitutableT(name)
        }
      }
      case ApplyFunction(func, input) => {
        for {
          evalInput <- this(input, env)
          evalFunc <- this(func, env)
          functionApplied <- applyFunctionAttempt(evalFunc, evalInput, env)
          result <- convertObjectToType(functionApplied, env)
        } yield result
      }
      case StructType(params) => {
        for {
          mapInstance <- this(params, env)

          values <- mapInstance match {
            case MapInstance(v, Index(1)) => Success(v)
            case _ => Failure("Map Instance could not be resolved")
          }

          newParams <- convertMapInstanceStructToParams(values, env)
        } yield {
          StructT(newParams)
        }
      }
      case CaseType(params) => {
        // TODO - this is repeated code from StructType
        for {
          mapInstance <- this(params, env)

          values <- mapInstance match {
            case MapInstance(v, Index(0)) => Success(v)
            case MapInstance(v, default) => Failure("Map Instance " + mapInstance + " has the wrong default: " + default)
            case _ => Failure("Map Instance could not be resolved for params " + params + "\nInstead recieved: " + mapInstance)
          }

          newParams <- convertMapInstanceStructToParams(values, env)
        } yield {
          CaseT(newParams)
        }
      }
      case _ => {
        // TODO: Need to explicitly handle every case
        Failure("Couldn't convert into type: " + objectFound + " -- could be unimplemented")
      }
    }
  }

  def convertMapInstanceStructToParams(
    values: Vector[(NewMapObject, NewMapObject)],
    env: Environment
  ): Outcome[Vector[(String, NewMapType)], String] = {
    values match {
      case (key, value) +: restOfValues => key match {
        case IdentifierInstance(s) => {
          for {
            valueType <- convertObjectToType(value, env)
            restOfParams <- convertMapInstanceStructToParams(restOfValues, env.newParam(s, valueType))
          } yield {
            (s -> valueType) +: restOfParams
          }
        }
        // TODO - what if the key substitutes to an identifier?? Better Logic on that
        case _ => Failure("Key must be identifier: " + key)
      }
      case _ => {
        Success(Vector.empty)
      }
    }
  }
}