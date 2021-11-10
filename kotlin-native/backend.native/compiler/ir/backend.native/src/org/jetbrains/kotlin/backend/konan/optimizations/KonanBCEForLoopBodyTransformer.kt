/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.optimizations

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.lower.loops.*
import org.jetbrains.kotlin.backend.konan.ir.KonanNameConventions
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.util.OperatorNameConventions

internal class SymbolsInfo(val symbol: IrSymbol?, val receiversList: MutableList<IrSymbol?>) {
    fun intersects(other: SymbolsInfo): Boolean {
        if (symbol != other.symbol)
            return false
        val receiversSize = minOf(receiversList.size, other.receiversList.size)

        val zippedReceivers = receiversList.takeLast(receiversSize).zip(other.receiversList.takeLast(receiversSize)).reversed()

        zippedReceivers.forEach {
            if (it.first != it.second)
                return false
        }
        return true
    }
}

// Class contains information about analyzed loop.
internal class BoundsCheckAnalysisResult(val boundsAreSafe: Boolean, val arraySymbolsInfo: SymbolsInfo?)
// TODO: support `forEachIndexed`. Function is inlined and index is separate variable which isn't connected with loop induction variable.
/**
 * Transformer for loops bodies replacing get/set operators on analogs without bounds check where it's possible.
 */
class KonanBCEForLoopBodyTransformer : ForLoopBodyTransformer() {
    lateinit var mainLoopVariable: IrVariable
    lateinit var loopHeader: ForLoopHeader
    lateinit var loopVariableComponents: Map<Int, IrVariable>
    lateinit var context: CommonBackendContext

    private var analysisResult: BoundsCheckAnalysisResult = BoundsCheckAnalysisResult(false, null)

    override fun transform(context: CommonBackendContext, loopBody: IrExpression, loopVariable: IrVariable,
                           forLoopHeader: ForLoopHeader, loopComponents: Map<Int, IrVariable>) {
        this.context = context
        mainLoopVariable = loopVariable
        loopHeader = forLoopHeader
        loopVariableComponents = loopComponents
        analysisResult = analyzeLoopHeader(loopHeader)
        if (analysisResult.boundsAreSafe && analysisResult.arraySymbolsInfo != null)
            loopBody.transformChildrenVoid(this)
    }

    private inline fun IrGetValue.compareConstValue(compare: (IrExpression) -> Boolean): Boolean {
        val variable = symbol.owner
        return if (variable is IrVariable && !variable.isVar && variable.initializer != null) {
            compare(variable.initializer!!)
        } else false
    }

    private fun IrExpression.compareIntegerNumericConst(compare: (Long) -> Boolean): Boolean {
        @Suppress("UNCHECKED_CAST")
        return when (this) {
            is IrConst<*> -> value is Number && compare((value as Number).toLong())
            is IrGetValue -> compareConstValue { it.compareIntegerNumericConst(compare) }
            else -> false
        }
    }

    private fun IrExpression.compareFloatNumericConst(compare: (Double) -> Boolean): Boolean {
        @Suppress("UNCHECKED_CAST")
        return when (this) {
            is IrConst<*> -> value is Number && compare((value as Number).toDouble())
            is IrGetValue -> compareConstValue { it.compareFloatNumericConst(compare) }
            else -> false
        }
    }

    private fun IrType.isBasicArray() = isPrimitiveArray() || isArray() || isUnsignedArray()

    private fun IrCall.isGetSizeCall() = dispatchReceiver?.type?.isBasicArray() == true &&
            symbol.owner == dispatchReceiver!!.type.getClass()!!.getPropertyGetter("size")!!.owner

    private fun IrCall.dispatchReceiverIsGetSizeCall() = (dispatchReceiver as? IrCall)?.let { it.isGetSizeCall() } ?: false

    private fun lessThanSize(functionCall: IrCall): BoundsCheckAnalysisResult {
        val boundsAreSafe = when (functionCall.symbol.owner.name) {
            OperatorNameConventions.DEC ->
                functionCall.dispatchReceiverIsGetSizeCall()
            OperatorNameConventions.MINUS -> {
                val value = functionCall.getValueArgument(0)
                functionCall.dispatchReceiverIsGetSizeCall() &&
                        value?.compareIntegerNumericConst { it > 0 } == true
            }
            OperatorNameConventions.DIV -> {
                val value = functionCall.getValueArgument(0)
                functionCall.dispatchReceiverIsGetSizeCall() &&
                        value?.compareFloatNumericConst { it > 1 } == true
            }
            else -> false
        }
        return if (boundsAreSafe)
            BoundsCheckAnalysisResult(boundsAreSafe,
                    (functionCall.dispatchReceiver as? IrCall)?.dispatchReceiver?.let { getSymbolsInfo(it) })
        else BoundsCheckAnalysisResult(boundsAreSafe, null)
    }

    private inline fun checkIrGetValue(value: IrGetValue, condition: (IrExpression) -> BoundsCheckAnalysisResult): BoundsCheckAnalysisResult {
        val variable = value.symbol.owner
        return if (variable is IrVariable && !variable.isVar && variable.initializer != null) {
            condition(variable.initializer!!)
        } else {
            BoundsCheckAnalysisResult(false, null)
        }
    }

    private fun checkIrCallCondition(expression: IrExpression, condition: (IrCall) -> BoundsCheckAnalysisResult): BoundsCheckAnalysisResult =
            when (expression) {
                is IrCall -> {
                    expression.symbol.owner.correspondingPropertySymbol?.let {
                        // Case of property accessor.
                        (getSymbolsInfo(expression)?.symbol?.owner as? IrProperty)?.backingField?.initializer?.expression?.let {
                            checkIrCallCondition(it, condition)
                        }
                    } ?: condition(expression)
                }
                is IrGetValue -> checkIrGetValue(expression) { valueInitializer -> checkIrCallCondition(valueInitializer, condition) }
                else -> BoundsCheckAnalysisResult(false, null)
            }

    private val IrProperty.isWithoutSideEffects: Boolean
        get()  {
            if (isVar || isDelegated)
                return false

            val withBackingFiled = backingField?.let { true } ?:
                // Analyze inheritance.
                if (isFakeOverride)
                    resolveFakeOverride()?.isWithoutSideEffects
                else false

            return withBackingFiled ?: false
        }

    private fun getSymbolsInfo(expression: IrExpression): SymbolsInfo? {
        return when (expression) {
            is IrGetValue -> {
                when (val declaration = expression.symbol.owner) {
                    is IrVariable -> {
                        if (declaration.isVar) return null
                        val initializerSymbol = declaration.initializer?.let { getSymbolsInfo(it) }
                        initializerSymbol ?: SymbolsInfo(expression.symbol, mutableListOf())
                    }
                    is IrValueParameter ->
                        if (declaration.origin == IrDeclarationOrigin.INSTANCE_RECEIVER)
                            SymbolsInfo(expression.symbol, mutableListOf())
                        else null
                    else -> null
                }
            }
            is IrCall -> {
                val propertySymbol = expression.symbol.owner.correspondingPropertySymbol

                if (propertySymbol == null || !propertySymbol.owner.isWithoutSideEffects)
                    return null

                val symbolsInfo = propertySymbol.owner.backingField?.initializer?.expression?.let { getSymbolsInfo(it) }
                        ?: SymbolsInfo(propertySymbol, mutableListOf())

                val symbolsFromDispatchReceiver = expression.dispatchReceiver?.let { getSymbolsInfo(it) ?: return null }
                        ?: SymbolsInfo(null, mutableListOf())

                SymbolsInfo(symbolsInfo.symbol, symbolsFromDispatchReceiver.receiversList.apply {
                    add(symbolsFromDispatchReceiver.symbol)
                    addAll(symbolsInfo.receiversList.filterNot { symbol ->
                        with(symbol?.owner as? IrDeclarationWithName) {
                            this?.let { it.origin == IrDeclarationOrigin.INSTANCE_RECEIVER && it.name.asString() == "<this>" } ?: false
                        }
                    })
                })
            }
            is IrGetObjectValue -> {
                SymbolsInfo(expression.symbol, mutableListOf())
            }
            else -> null
        }
    }

    private fun checkLastElement(last: IrExpression, loopHeader: ProgressionLoopHeader): BoundsCheckAnalysisResult =
            checkIrCallCondition(last) { call ->
                if (call.isGetSizeCall() && !loopHeader.headerInfo.isLastInclusive) {
                    BoundsCheckAnalysisResult(true, call.dispatchReceiver?.let { getSymbolsInfo(it) })
                } else {
                    lessThanSize(call)
                }
            }

    private fun IrExpression.isProgressionPropertyGetter(propertyName: String) =
            this is IrCall && symbol.owner.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR &&
                    (symbol.signature as? IdSignature.AccessorSignature)?.propertySignature?.asPublic()?.shortName == propertyName &&
                    dispatchReceiver?.type?.getClass()?.symbol in context.ir.symbols.progressionClasses

    private fun analyzeLoopHeader(loopHeader: ForLoopHeader): BoundsCheckAnalysisResult {
        var analysisResult = BoundsCheckAnalysisResult(false, null)
        when (loopHeader) {
            is ProgressionLoopHeader ->
                when (loopHeader.headerInfo.direction) {
                    ProgressionDirection.INCREASING -> {
                        // Analyze first element of progression.
                        if (!loopHeader.headerInfo.first.compareIntegerNumericConst { it >= 0 }) {
                            return analysisResult
                        }
                        // TODO: variable set to const value and field getters. Add constant propagation?
                        // Analyze last element of progression.
                        if (loopHeader.headerInfo.last is IrCall) {
                            val functionCall = (loopHeader.headerInfo.last as IrCall)
                            // Case of range with step - `for (i in 0..array.size - 1 step n)`.
                            // There is a temporary variable `val nestedLast = array.size - 1`
                            // and `last` is computed as `getProgressionLastElement(0, nestedLast, n)`
                            if (loopHeader.headerInfo.progressionType.getProgressionLastElementFunction == functionCall.symbol) {
                                val nestedLastVariable = functionCall.getValueArgument(1)
                                if (nestedLastVariable is IrGetValue && nestedLastVariable.symbol.owner is IrVariable) {
                                    val nestedLast = (nestedLastVariable.symbol.owner as IrVariable).initializer
                                    analysisResult = checkLastElement(nestedLast!!, loopHeader)
                                }
                            } else {
                                // Simple progression.
                                analysisResult = checkLastElement(functionCall, loopHeader)
                            }
                        } else {
                            analysisResult = checkLastElement(loopHeader.headerInfo.last, loopHeader)
                        }
                    }
                    ProgressionDirection.DECREASING -> {
                        val valueToCompare = if (loopHeader.headerInfo.isLastInclusive) 0 else -1
                        var boundsAreSafe = false
                        if (loopHeader.headerInfo.last is IrCall) {
                            val functionCall = (loopHeader.headerInfo.last as IrCall)
                            // Case of range with step - for (i in array.size - 1 downTo 0 step n).
                            // There is a temporary variable `val nestedFirst = array.size - 1`
                            // and `last` is computed as `getProgressionLastElement(nestedFirst, 0, n)`
                            if (loopHeader.headerInfo.progressionType.getProgressionLastElementFunction == functionCall.symbol) {
                                if (functionCall.getValueArgument(1)?.compareIntegerNumericConst { it >= valueToCompare } == true) {
                                    boundsAreSafe = true
                                }
                            }
                        } else if (loopHeader.headerInfo.last.compareIntegerNumericConst { it >= valueToCompare }) {
                            boundsAreSafe = true
                        }
                        if (!boundsAreSafe)
                            return analysisResult

                        analysisResult = checkIrCallCondition(loopHeader.headerInfo.first, ::lessThanSize)
                    }
                    ProgressionDirection.UNKNOWN ->
                        // Case of progression - for (i in 0 until array.size step n)
                        if (loopHeader.headerInfo.first.isProgressionPropertyGetter("first") &&
                                loopHeader.headerInfo.last.isProgressionPropertyGetter("last")) {
                            val firstReceiver = (loopHeader.headerInfo.first as IrCall).dispatchReceiver as? IrGetValue
                            val lastReceiver = (loopHeader.headerInfo.last as IrCall).dispatchReceiver as? IrGetValue
                            if (firstReceiver?.symbol?.owner == lastReceiver?.symbol?.owner) {
                                val untilFunction =
                                        ((firstReceiver?.symbol?.owner as? IrVariable)?.initializer as? IrCall)?.extensionReceiver as? IrCall
                                if (untilFunction?.symbol?.owner?.name?.asString() == "until" && untilFunction.extensionReceiver?.compareIntegerNumericConst { it >= 0 } == true) {
                                    val last = untilFunction.getValueArgument(0)!!
                                    analysisResult = checkIrCallCondition(last) { call ->
                                        // `isLastInclusive` for current case is set to true.
                                        // This case isn't fully optimized in ForLoopsLowering.
                                        if (call.isGetSizeCall())
                                            BoundsCheckAnalysisResult(true, call.dispatchReceiver?.let { getSymbolsInfo(it) } )
                                        else
                                            lessThanSize(call)
                                    }
                                }
                            }
                        }
                }

            is WithIndexLoopHeader ->
                when (loopHeader.nestedLoopHeader) {
                    is IndexedGetLoopHeader -> {
                        analysisResult = BoundsCheckAnalysisResult(true,
                                (loopHeader.loopInitStatements[0] as? IrVariable)?.initializer?.let { getSymbolsInfo(it) })
                    }
                    is ProgressionLoopHeader -> analysisResult = analyzeLoopHeader(loopHeader.nestedLoopHeader)
                }
        }
        return analysisResult
    }

    private fun replaceOperators(expression: IrCall, index: IrExpression, safeIndexVariables: List<IrVariable>): IrExpression {
        if (index is IrGetValue && index.symbol.owner in safeIndexVariables) {
            val operatorWithoutBoundCheck = expression.dispatchReceiver!!.type.getClass()!!.functions.singleOrNull {
                if (expression.symbol.owner.name == OperatorNameConventions.SET)
                    it.name == KonanNameConventions.setWithoutBoundCheck
                else
                    it.name == KonanNameConventions.getWithoutBoundCheck
            } ?: return expression
            return IrCallImpl(
                    expression.startOffset, expression.endOffset, expression.type, operatorWithoutBoundCheck.symbol,
                    typeArgumentsCount = expression.typeArgumentsCount,
                    valueArgumentsCount = expression.valueArgumentsCount).apply {
                dispatchReceiver = expression.dispatchReceiver
                for (argIndex in 0 until expression.valueArgumentsCount) {
                    putValueArgument(argIndex, expression.getValueArgument(argIndex))
                }
            }
        }
        return expression
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val newExpression = super.visitCall(expression)
        require(newExpression is IrCall)
        if (expression.symbol.owner.name != OperatorNameConventions.SET && expression.symbol.owner.name != OperatorNameConventions.GET)
            return newExpression
        if (expression.dispatchReceiver == null || expression.dispatchReceiver?.type?.isBasicArray() != true ||
                getSymbolsInfo(expression.dispatchReceiver!!)?.intersects(analysisResult.arraySymbolsInfo!!) != true)
            return newExpression
        // Analyze arguments of set/get operator.
        val index = newExpression.getValueArgument(0)!!
        return when (loopHeader) {
            is ProgressionLoopHeader -> with(loopHeader as ProgressionLoopHeader) {
                replaceOperators(newExpression, index, listOf(mainLoopVariable, inductionVariable))
            }

            is WithIndexLoopHeader -> with(loopHeader as WithIndexLoopHeader) {
                when (nestedLoopHeader) {
                    is IndexedGetLoopHeader ->
                        replaceOperators(newExpression, index, listOfNotNull(indexVariable, loopVariableComponents[1]))
                    is ProgressionLoopHeader ->
                        // Case of `for ((index, value) in (0..array.size - 1 step n).withIndex())`.
                        // Both `index` (progression size less than array size)
                        // and `value` (progression start and end element are inside bounds)
                        // are safe variables if use them in get/set operators.
                        replaceOperators(newExpression, index,
                                listOfNotNull(indexVariable, loopVariableComponents[1], loopVariableComponents[2])
                        )
                    else -> newExpression
                }
            }

            else -> newExpression
        }
    }
}