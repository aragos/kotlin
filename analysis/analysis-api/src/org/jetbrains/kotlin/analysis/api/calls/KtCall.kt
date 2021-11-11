/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.calls

import org.jetbrains.kotlin.analysis.api.ValidityTokenOwner
import org.jetbrains.kotlin.analysis.api.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.analysis.api.symbols.KtFunctionLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtVariableLikeSymbol
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.types.KtSubstitutor
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Represents direct or indirect (via invoke) function call from Kotlin code
 */
public sealed class KtCall : ValidityTokenOwner {
    public abstract val isErrorCall: Boolean
    public abstract val argumentMapping: LinkedHashMap<KtExpression, KtValueParameterSymbol>
    public abstract val targetFunction: KtCallTarget
    public abstract val substitutor: KtSubstitutor
    public abstract val dispatchReceiver: KtReceiverValue?
    public abstract val extensionReceiver: KtReceiverValue?
}


public sealed class KtReceiverValue : ValidityTokenOwner

public class KtExplicitReceiverValue(override val token: ValidityToken, public val expression: KtExpression) : KtReceiverValue()

public class KtImplicitReceiverValue(override val token: ValidityToken, private val _boundSymbol: KtSymbol) : KtReceiverValue() {
    public val boundSymbol: KtSymbol get() = withValidityAssertion { _boundSymbol }
}

public class KtSmartCastReceiverValue(public val original: KtReceiverValue, private val _smartCastType: KtType) : KtReceiverValue() {
    override val token: ValidityToken
        get() = original.token
    public val smartCastType: KtType get() = withValidityAssertion { _smartCastType }
}

public val KtReceiverValue.boundSymbol: KtSymbol?
    get() = when (this) {
        is KtExplicitReceiverValue -> null
        is KtImplicitReceiverValue -> boundSymbol
        is KtSmartCastReceiverValue -> original.boundSymbol
    }

public val KtReceiverValue.expression: KtExpression?
    get() = when (this) {
        is KtExplicitReceiverValue -> expression
        is KtImplicitReceiverValue -> null
        is KtSmartCastReceiverValue -> original.expression
    }

/**
 * Call using `()` of some variable of functional type, e.g.,
 *
 * fun x(f: () -> Int) {
 *    f() // functional type call
 * }
 */
public class KtFunctionalTypeVariableCall(
    private val _target: KtVariableLikeSymbol,
    private val _argumentMapping: LinkedHashMap<KtExpression, KtValueParameterSymbol>,
    private val _targetFunction: KtCallTarget,
    private val _substitutor: KtSubstitutor,
    private val _dispatchReceiver: KtReceiverValue?,
    private val _extensionReceiver: KtReceiverValue?,
    override val token: ValidityToken
) : KtCall() {
    public val target: KtVariableLikeSymbol get() = withValidityAssertion { _target }
    override val isErrorCall: Boolean get() = withValidityAssertion { false }
    override val argumentMapping: LinkedHashMap<KtExpression, KtValueParameterSymbol>
        get() = withValidityAssertion { _argumentMapping }
    override val targetFunction: KtCallTarget
        get() = withValidityAssertion { _targetFunction }
    override val substitutor: KtSubstitutor
        get() = withValidityAssertion { _substitutor }
    override val dispatchReceiver: KtReceiverValue?
        get() = withValidityAssertion { _dispatchReceiver }
    override val extensionReceiver: KtReceiverValue?
        get() = withValidityAssertion { _extensionReceiver }
}

/**
 * Direct or indirect call of function declared by user
 */
public sealed class KtDeclaredFunctionCall : KtCall() {
    override val isErrorCall: Boolean
        get() = withValidityAssertion { targetFunction is KtErrorCallTarget }
}

/**
 * Call using () on variable on some non-functional type, considers that `invoke` function is declared somewhere
 *
 * fun x(y: Int) {
 *    y() // variable with invoke function call
 * }
 *
 * fun Int.invoke() {}
 */
public class KtVariableWithInvokeFunctionCall(
    private val _target: KtVariableLikeSymbol,
    private val _argumentMapping: LinkedHashMap<KtExpression, KtValueParameterSymbol>,
    private val _targetFunction: KtCallTarget,
    private val _substitutor: KtSubstitutor,
    private val _dispatchReceiver: KtReceiverValue?,
    private val _extensionReceiver: KtReceiverValue?,
    override val token: ValidityToken
) : KtDeclaredFunctionCall() {
    public val target: KtVariableLikeSymbol get() = withValidityAssertion { _target }
    override val argumentMapping: LinkedHashMap<KtExpression, KtValueParameterSymbol>
        get() = withValidityAssertion { _argumentMapping }
    override val targetFunction: KtCallTarget
        get() = withValidityAssertion { _targetFunction }
    override val substitutor: KtSubstitutor
        get() = withValidityAssertion { _substitutor }
    override val dispatchReceiver: KtReceiverValue?
        get() = withValidityAssertion { _dispatchReceiver }
    override val extensionReceiver: KtReceiverValue?
        get() = withValidityAssertion { _extensionReceiver }
}

/**
 * Simple function call, e.g.,
 *
 * x.toString() // function call
 */
public class KtFunctionCall(
    private val _argumentMapping: LinkedHashMap<KtExpression, KtValueParameterSymbol>,
    private val _targetFunction: KtCallTarget,
    private val _substitutor: KtSubstitutor,
    private val _dispatchReceiver: KtReceiverValue?,
    private val _extensionReceiver: KtReceiverValue?,
    override val token: ValidityToken
) : KtDeclaredFunctionCall() {
    override val argumentMapping: LinkedHashMap<KtExpression, KtValueParameterSymbol>
        get() = withValidityAssertion { _argumentMapping }
    override val targetFunction: KtCallTarget
        get() = withValidityAssertion { _targetFunction }
    override val substitutor: KtSubstitutor
        get() = withValidityAssertion { _substitutor }
    override val dispatchReceiver: KtReceiverValue?
        get() = withValidityAssertion { _dispatchReceiver }
    override val extensionReceiver: KtReceiverValue?
        get() = withValidityAssertion { _extensionReceiver }
}

/**
 * Annotation call, e.g.,
 *
 * @Retention(AnnotationRetention.SOURCE) // annotation call
 * annotation class Ann
 */
public class KtAnnotationCall(
    private val _argumentMapping: LinkedHashMap<KtExpression, KtValueParameterSymbol>,
    private val _targetFunction: KtCallTarget,
    override val token: ValidityToken
) : KtDeclaredFunctionCall() {
    override val argumentMapping: LinkedHashMap<KtExpression, KtValueParameterSymbol>
        get() = withValidityAssertion { _argumentMapping }
    override val targetFunction: KtCallTarget
        get() = withValidityAssertion { _targetFunction }

    // Type parameter is allowed for an annotation class but not allowed as members. So substitutor is probably never useful.
    override val substitutor: KtSubstitutor = KtSubstitutor.Empty(token)
    override val dispatchReceiver: KtReceiverValue?
        get() = null
    override val extensionReceiver: KtReceiverValue?
        get() = null
}
// TODO: Add other properties, e.g., useSiteTarget

/**
 * Delegated constructor call, e.g.,
 *
 * open class A(a: Int)
 * class B(b: Int) : A(b) { // delegated constructor call (kind = SUPER_CALL)
 *   constructor() : this(1) // delegated constructor call (kind = THIS_CALL)
 * }
 */
public class KtDelegatedConstructorCall(
    private val _argumentMapping: LinkedHashMap<KtExpression, KtValueParameterSymbol>,
    private val _targetFunction: KtCallTarget,
    public val kind: KtDelegatedConstructorCallKind,
    override val token: ValidityToken
) : KtDeclaredFunctionCall() {
    override val argumentMapping: LinkedHashMap<KtExpression, KtValueParameterSymbol>
        get() = withValidityAssertion { _argumentMapping }
    override val targetFunction: KtCallTarget
        get() = withValidityAssertion { _targetFunction }

    // A delegate constructor call never has any type argument.
    override val substitutor: KtSubstitutor = KtSubstitutor.Empty(token)
    override val dispatchReceiver: KtReceiverValue?
        get() = null
    override val extensionReceiver: KtReceiverValue?
        get() = null
}

public enum class KtDelegatedConstructorCallKind { SUPER_CALL, THIS_CALL }

/**
 * Represents function(s) in which call was resolved,
 * Can be success [KtSuccessCallTarget] in this case there only one such function
 * Or erroneous [KtErrorCallTarget] in this case there can be any count of candidates
 */
public sealed class KtCallTarget : ValidityTokenOwner {
    public abstract val candidates: Collection<KtFunctionLikeSymbol>
}

/**
 * Success call of [symbol]
 */
public class KtSuccessCallTarget(private val _symbol: KtFunctionLikeSymbol, override val token: ValidityToken) : KtCallTarget() {
    public val symbol: KtFunctionLikeSymbol get() = withValidityAssertion { _symbol }
    override val candidates: Collection<KtFunctionLikeSymbol> get() = withValidityAssertion { listOf(symbol) }
}

/**
 * Function call with errors, possible candidates are [candidates]
 */
public class KtErrorCallTarget(
    private val _candidates: Collection<KtFunctionLikeSymbol>,
    private val _diagnostic: KtDiagnostic,
    override val token: ValidityToken
) : KtCallTarget() {
    public val diagnostic: KtDiagnostic get() = withValidityAssertion { _diagnostic }
    override val candidates: Collection<KtFunctionLikeSymbol> get() = withValidityAssertion { _candidates }
}