// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.kotlin.idea.inspections.dfa

import com.intellij.codeInspection.dataFlow.TypeConstraints
import com.intellij.codeInspection.dataFlow.interpreter.DataFlowInterpreter
import com.intellij.codeInspection.dataFlow.java.inst.*
import com.intellij.codeInspection.dataFlow.jvm.SpecialField
import com.intellij.codeInspection.dataFlow.jvm.transfer.ExceptionTransfer
import com.intellij.codeInspection.dataFlow.jvm.transfer.InstructionTransfer
import com.intellij.codeInspection.dataFlow.lang.ir.*
import com.intellij.codeInspection.dataFlow.lang.ir.ControlFlow.DeferredOffset
import com.intellij.codeInspection.dataFlow.memory.DfaMemoryState
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeBinOp
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet
import com.intellij.codeInspection.dataFlow.types.DfBooleanType
import com.intellij.codeInspection.dataFlow.types.DfIntegralType
import com.intellij.codeInspection.dataFlow.types.DfType
import com.intellij.codeInspection.dataFlow.types.DfTypes
import com.intellij.codeInspection.dataFlow.value.DfaControlTransferValue
import com.intellij.codeInspection.dataFlow.value.DfaControlTransferValue.Trap
import com.intellij.codeInspection.dataFlow.value.DfaValueFactory
import com.intellij.codeInspection.dataFlow.value.RelationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.*
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.FList
import com.intellij.util.containers.FactoryMap
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.idea.intentions.loopToCallChain.targetLoop
import org.jetbrains.kotlin.idea.refactoring.move.moveMethod.type
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.isDouble
import org.jetbrains.kotlin.types.typeUtil.isFloat
import org.jetbrains.kotlin.types.typeUtil.isLong
import org.jetbrains.kotlin.types.typeUtil.makeNotNullable
import java.util.concurrent.atomic.AtomicInteger

class KtControlFlowBuilder(val factory: DfaValueFactory, val context: KtExpression) {
    // Will be used to track catch/finally blocks
    private val traps: FList<Trap> = FList.emptyList()
    private val flow = ControlFlow(factory, context)
    private var broken: Boolean = false
    private val exceptionCache = FactoryMap.create<String, ExceptionTransfer>
    { fqn -> ExceptionTransfer(TypeConstraints.instanceOf(createClassType(fqn))) }
    private val stringType = createClassType(CommonClassNames.JAVA_LANG_STRING)

    private fun createClassType(fqn: String): PsiClassType {
        val project = factory.project
        val scope = context.resolveScope
        val aClass = JavaPsiFacade.getInstance(project).findClass(fqn, scope)
        val elementFactory = JavaPsiFacade.getElementFactory(project)
        return if (aClass != null) elementFactory.createType(aClass) else elementFactory.createTypeByFQClassName(fqn, scope)
    }

    fun buildFlow(): ControlFlow? {
        processExpression(context)
        if (LOG.isDebugEnabled) {
            val total = totalCount.incrementAndGet()
            val success = if (!broken) successCount.incrementAndGet() else successCount.get()
            if (total % 100 == 0) {
                LOG.info("Analyzed: "+success+" of "+total + " ("+success*100/total + "%)")
            }
        }
        if (broken) return null
        addInstruction(PopInstruction()) // return value
        flow.finish()
        return flow
    }

    private fun processExpression(expr: KtExpression?) {
        when (expr) {
            null -> pushUnknown()
            is KtBlockExpression -> processBlock(expr)
            is KtParenthesizedExpression -> processExpression(expr.expression)
            is KtBinaryExpression -> processBinaryExpression(expr)
            is KtPrefixExpression -> processPrefixExpression(expr)
            is KtPostfixExpression -> processPostfixExpression(expr)
            is KtCallExpression -> processCallExpression(expr)
            is KtConstantExpression -> processConstantExpression(expr)
            is KtSimpleNameExpression -> processReferenceExpression(expr)
            is KtDotQualifiedExpression -> processQualifiedReferenceExpression(expr)
            is KtSafeQualifiedExpression -> processQualifiedReferenceExpression(expr)
            is KtReturnExpression -> processReturnExpression(expr)
            is KtContinueExpression -> processLabeledJumpExpression(expr)
            is KtBreakExpression -> processLabeledJumpExpression(expr)
            is KtThrowExpression -> processThrowExpression(expr)
            is KtIfExpression -> processIfExpression(expr)
            is KtWhenExpression -> processWhenExpression(expr)
            is KtWhileExpression -> processWhileExpression(expr)
            is KtDoWhileExpression -> processDoWhileExpression(expr)
            is KtForExpression -> processForExpression(expr)
            is KtProperty -> processDeclaration(expr)
            is KtLambdaExpression -> processLambda(expr)
            is KtStringTemplateExpression -> processStringTemplate(expr)
            // when, try, anonymous classes, local functions
            // as, as?, is, is?, in
            else -> {
                // unsupported construct
                broken = true
            }
        }
        flow.finishElement(expr)
    }

    private fun processStringTemplate(expr: KtStringTemplateExpression) {
        var first = true
        val entries = expr.entries
        if (entries.isEmpty()) {
            addInstruction(PushValueInstruction(DfTypes.constant("", stringType)))
            return
        }
        val lastEntry = entries.last()
        for (entry in entries) {
            when (entry) {
                is KtEscapeStringTemplateEntry ->
                    addInstruction(PushValueInstruction(DfTypes.constant(entry.unescapedValue, stringType)))
                is KtLiteralStringTemplateEntry ->
                    addInstruction(PushValueInstruction(DfTypes.constant(entry.text, stringType)))
                is KtStringTemplateEntryWithExpression ->
                    processExpression(entry.expression)
                else ->
                    pushUnknown()
            }
            if (!first) {
                val anchor = if (entry == lastEntry) KotlinExpressionAnchor(expr) else null
                addInstruction(StringConcatInstruction(anchor, stringType))
            }
            first = false
        }
    }

    private fun processLambda(expr: KtLambdaExpression) {
        addInstruction(ClosureInstruction(listOf(expr)))
        pushUnknown()
    }

    private fun processCallExpression(expr: KtCallExpression) {
        val args = expr.valueArgumentList?.arguments
        if (args != null) {
            for (arg: KtValueArgument in args) {
                val argExpr = arg.getArgumentExpression()
                if (argExpr != null) {
                    processExpression(argExpr)
                    addInstruction(PopInstruction())
                }
            }
        }
        for(lambdaArg in expr.lambdaArguments) {
            processExpression(lambdaArg.getLambdaExpression())
            addInstruction(PopInstruction())
        }
        pushUnknown()
        addInstruction(FlushFieldsInstruction())
        // TODO: support pure calls, some known methods, probably Java contracts, etc.
    }

    private fun processQualifiedReferenceExpression(expr: KtQualifiedExpression) {
        // TODO: support qualified references as variables
        processExpression(expr.receiverExpression)
        val specialField = if (expr is KtDotQualifiedExpression) findSpecialField(expr) else null
        if (specialField != null) {
            addInstruction(UnwrapDerivedVariableInstruction(specialField))
        } else {
            addInstruction(PopInstruction())
            processExpression(expr.selectorExpression)
            addInstruction(PopInstruction())
            pushUnknown()
        }
    }

    private fun findSpecialField(expr: KtQualifiedExpression): SpecialField? {
        val selector = expr.selectorExpression ?: return null
        val receiver = expr.receiverExpression
        when (selector.text) {
            "size" -> {
                val type = receiver.getKotlinType() ?: return null
                when {
                    KotlinBuiltIns.isArray(type) || KotlinBuiltIns.isPrimitiveArray(type) -> {
                        return SpecialField.ARRAY_LENGTH
                    }
                    KotlinBuiltIns.isCollectionOrNullableCollection(type) || KotlinBuiltIns.isMapOrNullableMap(type) -> {
                        return SpecialField.COLLECTION_SIZE
                    }
                    else -> return null
                }
            }
            "length" -> {
                val type = receiver.getKotlinType() ?: return null
                return when {
                    KotlinBuiltIns.isStringOrNullableString(type) -> SpecialField.STRING_LENGTH
                    else -> null
                }
            }
            else -> return null
        }
    }

    private fun processPrefixExpression(expr: KtPrefixExpression) {
        val operand = expr.baseExpression
        processExpression(operand)
        val anchor = KotlinExpressionAnchor(expr)
        if (operand != null) {
            val dfType = operand.getKotlinType().toDfType(expr)
            val descriptor = KtLocalVariableDescriptor.create(operand)
            val ref = expr.operationReference.text
            if (dfType is DfIntegralType) {
                when (ref) {
                    "++", "--" -> {
                        if (descriptor != null) {
                            addInstruction(PushValueInstruction(dfType.meetRange(LongRangeSet.point(1))))
                            addInstruction(NumericBinaryInstruction(if (ref == "++") LongRangeBinOp.PLUS else LongRangeBinOp.MINUS, null))
                            addInstruction(SimpleAssignmentInstruction(anchor, factory.varFactory.createVariableValue(descriptor)))
                            return
                        }
                    }
                    "+" -> {
                        return
                    }
                    "-" -> {
                        addInstruction(PushValueInstruction(dfType.meetRange(LongRangeSet.point(0))))
                        addInstruction(SwapInstruction())
                        addInstruction(NumericBinaryInstruction(LongRangeBinOp.MINUS, anchor))
                        return
                    }
                }
            }
            if (dfType is DfBooleanType && ref == "!") {
                addInstruction(NotInstruction(anchor))
                return
            }
            if (descriptor != null && (ref == "++" || ref == "--")) {
                // Custom inc/dec may update the variable
                addInstruction(FlushVariableInstruction(factory.varFactory.createVariableValue(descriptor)))
            }
        }
        addInstruction(EvalUnknownInstruction(anchor, 1))
    }

    private fun processPostfixExpression(expr: KtPostfixExpression) {
        val operand = expr.baseExpression
        processExpression(operand)
        val anchor = KotlinExpressionAnchor(expr)
        val ref = expr.operationReference.text
        if (ref == "++" || ref == "--") {
            if (operand != null) {
                val dfType = operand.getKotlinType().toDfType(expr)
                val descriptor = KtLocalVariableDescriptor.create(operand)
                if (descriptor != null) {
                    if (dfType is DfIntegralType) {
                        addInstruction(DupInstruction())
                        addInstruction(PushValueInstruction(dfType.meetRange(LongRangeSet.point(1))))
                        addInstruction(NumericBinaryInstruction(if (ref == "++") LongRangeBinOp.PLUS else LongRangeBinOp.MINUS, null))
                        addInstruction(SimpleAssignmentInstruction(anchor, factory.varFactory.createVariableValue(descriptor)))
                        addInstruction(PopInstruction())
                    } else {
                        // Custom inc/dec may update the variable
                        addInstruction(FlushVariableInstruction(factory.varFactory.createVariableValue(descriptor)))
                    }
                }
            }
        } else {
            // TODO: process !!
            addInstruction(EvalUnknownInstruction(anchor, 1))
        }
    }

    private fun processDoWhileExpression(expr: KtDoWhileExpression) {
        val offset = ControlFlow.FixedOffset(flow.instructionCount)
        processExpression(expr.body)
        addInstruction(PopInstruction())
        processExpression(expr.condition)
        addInstruction(ConditionalGotoInstruction(offset, DfTypes.TRUE))
        flow.finishElement(expr)
        pushUnknown()
        addInstruction(FinishElementInstruction(expr))
    }

    private fun processWhileExpression(expr: KtWhileExpression) {
        val startOffset = ControlFlow.FixedOffset(flow.instructionCount)
        val condition = expr.condition
        processExpression(condition)
        val endOffset = DeferredOffset()
        addInstruction(ConditionalGotoInstruction(endOffset, DfTypes.FALSE, condition))
        processExpression(expr.body)
        addInstruction(PopInstruction())
        addInstruction(GotoInstruction(startOffset))
        setOffset(endOffset)
        flow.finishElement(expr)
        pushUnknown()
        addInstruction(FinishElementInstruction(expr))
    }

    private fun processForExpression(expr: KtForExpression) {
        val range = expr.loopRange
        processExpression(range)
        addInstruction(PopInstruction())
        // TODO: process collections and integer ranges in a special way
        val startOffset = ControlFlow.FixedOffset(flow.instructionCount)
        val endOffset = DeferredOffset()
        pushUnknown()
        addInstruction(ConditionalGotoInstruction(endOffset, DfTypes.FALSE))
        val parameter = expr.loopParameter
        if (parameter == null) {
            // TODO: support destructuring declarations
            broken = true
            return
        }
        val descriptor = KtLocalVariableDescriptor(parameter)
        addInstruction(FlushVariableInstruction(factory.varFactory.createVariableValue(descriptor)))
        processExpression(expr.body)
        addInstruction(PopInstruction())
        addInstruction(GotoInstruction(startOffset))
        setOffset(endOffset)
        flow.finishElement(expr)
        pushUnknown()
        addInstruction(FinishElementInstruction(expr))
    }

    private fun processBlock(expr: KtBlockExpression) {
        val statements = expr.statements
        if (statements.isEmpty()) {
            pushUnknown()
        } else {
            for (child in statements) {
                processExpression(child)
                if (child != statements.last()) {
                    addInstruction(PopInstruction())
                }
                if (broken) return
            }
            addInstruction(FinishElementInstruction(expr))
        }
    }

    private fun processDeclaration(variable: KtProperty) {
        val initializer = variable.initializer
        if (initializer == null) {
            pushUnknown()
            return
        }
        val dfaVariable = factory.varFactory.createVariableValue(KtLocalVariableDescriptor(variable))
        processExpression(initializer)
        addImplicitConversion(initializer, variable.type())
        addInstruction(SimpleAssignmentInstruction(KotlinExpressionAnchor(variable), dfaVariable))
    }

    private fun processReturnExpression(expr: KtReturnExpression) {
        if (expr.labeledExpression != null) {
            // TODO: support labels
            broken = true
            return
        }
        processExpression(expr.returnedExpression)
        addInstruction(ReturnInstruction(factory, traps, expr))
        pushUnknown()
    }

    private fun getTrapsInsideElement(element: PsiElement): FList<Trap> {
        return FList.createFromReversed(traps.filter { trap -> PsiTreeUtil.isAncestor(element, trap.anchor, true) }.asReversed())
    }

    private fun createTransfer(exitedStatement: PsiElement, blockToFlush: PsiElement): InstructionTransfer {
        val varsToFlush = PsiTreeUtil.findChildrenOfType(
            blockToFlush,
            KtProperty::class.java
        ).map { property -> KtLocalVariableDescriptor(property) }
        return object : InstructionTransfer(flow.getEndOffset(exitedStatement), varsToFlush) {
            override fun dispatch(state: DfaMemoryState, interpreter: DataFlowInterpreter): MutableList<DfaInstructionState> {
                state.push(factory.unknown)
                return super.dispatch(state, interpreter)
            }
        }
    }

    private fun processLabeledJumpExpression(expr: KtExpressionWithLabel) {
        val targetLoop = expr.targetLoop()
        if (targetLoop == null || !PsiTreeUtil.isAncestor(context, targetLoop, false)) {
            addInstruction(ControlTransferInstruction(factory.controlTransfer(DfaControlTransferValue.RETURN_TRANSFER, traps)))
        } else {
            val body = if (expr is KtBreakExpression) targetLoop else targetLoop.body!!
            addInstruction(ControlTransferInstruction(factory.controlTransfer(createTransfer(body, body), getTrapsInsideElement(body))))
        }
    }

    private fun processThrowExpression(expr: KtThrowExpression) {
        val exception = expr.thrownExpression
        processExpression(exception)
        addInstruction(PopInstruction())
        if (exception != null) {
            val psiType = exception.getKotlinType()?.toPsiType(expr)
            if (psiType != null) {
                val kind = ExceptionTransfer(TypeConstraints.instanceOf(psiType))
                addInstruction(ThrowInstruction(factory.controlTransfer(kind, traps), expr))
                return
            }
        }
        pushUnknown()
    }

    private fun processReferenceExpression(expr: KtSimpleNameExpression) {
        val descriptor = KtLocalVariableDescriptor.create(expr)
        if (descriptor != null) {
            addInstruction(JvmPushInstruction(descriptor.createValue(factory, null), KotlinExpressionAnchor(expr)))
            val exprType = expr.getKotlinType()
            val declaredType = descriptor.variable.type()
            addImplicitConversion(expr, declaredType, exprType)
            return
        }
        addInstruction(FlushFieldsInstruction())
        pushUnknown()
    }

    private fun processConstantExpression(expr: KtConstantExpression) {
        addInstruction(PushValueInstruction(getConstant(expr), KotlinExpressionAnchor(expr)))
    }

    private fun pushUnknown() {
        addInstruction(PushValueInstruction(DfType.TOP))
    }

    private fun processBinaryExpression(expr: KtBinaryExpression) {
        val token = expr.operationToken
        val relation = relationFromToken(token)
        if (relation != null) {
            processBinaryRelationExpression(expr, relation, token == KtTokens.EXCLEQ || token == KtTokens.EQEQ)
            return
        }
        val leftKtType = expr.left?.getKotlinType()
        if (token === KtTokens.PLUS && (KotlinBuiltIns.isString(leftKtType) || KotlinBuiltIns.isString(expr.right?.getKotlinType()))) {
            processExpression(expr.left)
            processExpression(expr.right)
            addInstruction(StringConcatInstruction(KotlinExpressionAnchor(expr), stringType))
            return
        }
        if (leftKtType?.toDfType(expr) is DfIntegralType) {
            val mathOp = mathOpFromToken(expr.operationReference)
            if (mathOp != null) {
                processMathExpression(expr, mathOp)
                return
            }
        }
        if (token === KtTokens.ANDAND || token === KtTokens.OROR) {
            processShortCircuitExpression(expr, token === KtTokens.ANDAND)
            return
        }
        if (ASSIGNMENT_TOKENS.contains(token)) {
            processAssignmentExpression(expr)
            return
        }
        if (token === KtTokens.ELVIS) {
            processNullSafeOperator(expr)
            return
        }
        // TODO: support other operators
        processExpression(expr.left)
        processExpression(expr.right)
        addInstruction(EvalUnknownInstruction(KotlinExpressionAnchor(expr), 2))
        addInstruction(FlushFieldsInstruction())
    }

    private fun processNullSafeOperator(expr: KtBinaryExpression) {
        processExpression(expr.left)
        addInstruction(DupInstruction())
        val offset = DeferredOffset()
        addInstruction(ConditionalGotoInstruction(offset, DfTypes.NULL))
        val endOffset = DeferredOffset()
        addInstruction(GotoInstruction(endOffset))
        setOffset(offset)
        addInstruction(PopInstruction())
        processExpression(expr.right)
        setOffset(endOffset)
    }

    private fun processAssignmentExpression(expr: KtBinaryExpression) {
        val left = expr.left
        val right = expr.right
        val descriptor = KtLocalVariableDescriptor.create(left)
        val leftType = left?.getKotlinType()
        val rightType = right?.getKotlinType()
        if (descriptor == null) {
            processExpression(left)
            addInstruction(PopInstruction())
            processExpression(right)
            addImplicitConversion(right, leftType)
            // TODO: support qualified assignments
            addInstruction(FlushFieldsInstruction())
            return
        }
        val token = expr.operationToken
        val mathOp = mathOpFromAssignmentToken(token)
        if (mathOp != null) {
            val resultType = balanceType(leftType, rightType)
            processExpression(left)
            addImplicitConversion(left, resultType)
            processExpression(right)
            addImplicitConversion(right, resultType)
            addInstruction(NumericBinaryInstruction(mathOp, KotlinExpressionAnchor(expr)))
            addImplicitConversion(right, resultType, leftType)
        } else {
            processExpression(right)
            addImplicitConversion(right, leftType)
        }
        // TODO: support overloaded assignment
        addInstruction(SimpleAssignmentInstruction(KotlinExpressionAnchor(expr), factory.varFactory.createVariableValue(descriptor)))
        addInstruction(FinishElementInstruction(expr))
    }

    private fun processShortCircuitExpression(expr: KtBinaryExpression, and: Boolean) {
        val left = expr.left
        val right = expr.right
        val endOffset = DeferredOffset()
        processExpression(left)
        val nextOffset = DeferredOffset()
        addInstruction(ConditionalGotoInstruction(nextOffset, DfTypes.booleanValue(and), left))
        val anchor = KotlinExpressionAnchor(expr)
        addInstruction(PushValueInstruction(DfTypes.booleanValue(!and), anchor))
        addInstruction(GotoInstruction(endOffset))
        setOffset(nextOffset)
        addInstruction(FinishElementInstruction(null))
        processExpression(right)
        setOffset(endOffset)
        addInstruction(ResultOfInstruction(anchor))
    }

    private fun processMathExpression(expr: KtBinaryExpression, mathOp: LongRangeBinOp) {
        val left = expr.left
        val right = expr.right
        val resultType = expr.getKotlinType()
        processExpression(left)
        addImplicitConversion(left, resultType)
        processExpression(right)
        if (mathOp == LongRangeBinOp.DIV || mathOp == LongRangeBinOp.MOD) {
            val transfer: DfaControlTransferValue? = createTransfer("java.lang.ArithmeticException")
            val zero = if (resultType?.isLong() == true) DfTypes.longValue(0) else DfTypes.intValue(0)
            addInstruction(EnsureInstruction(null, RelationType.NE, zero, transfer, true))
        }
        if (!mathOp.isShift) {
            addImplicitConversion(right, resultType)
        }
        addInstruction(NumericBinaryInstruction(mathOp, KotlinExpressionAnchor(expr)))
    }

    fun createTransfer(exception: String): DfaControlTransferValue? {
        return if (!traps.isEmpty()) factory.controlTransfer(exceptionCache[exception], traps) else null
    }


    private fun addImplicitConversion(expression: KtExpression?, expectedType: KotlinType?) {
        addImplicitConversion(expression, expression?.getKotlinType(), expectedType)
    }

    private fun addImplicitConversion(expression: KtExpression?, actualType: KotlinType?, expectedType: KotlinType?) {
        expression ?: return
        actualType ?: return
        expectedType ?: return
        if (actualType == expectedType) return
        val actualPsiType = actualType.toPsiType(expression)
        val expectedPsiType = expectedType.toPsiType(expression)
        if (actualType.isMarkedNullable && !expectedType.isMarkedNullable && expectedPsiType is PsiPrimitiveType) {
            addInstruction(UnwrapDerivedVariableInstruction(SpecialField.UNBOX))
        }
        else if (!actualType.isMarkedNullable && expectedType.isMarkedNullable && actualPsiType is PsiPrimitiveType) {
            addInstruction(WrapDerivedVariableInstruction(
                expectedType.toDfType(expression).meet(DfTypes.NOT_NULL_OBJECT), SpecialField.UNBOX))
        }
        if (actualPsiType is PsiPrimitiveType && expectedPsiType is PsiPrimitiveType) {
            addInstruction(PrimitiveConversionInstruction(expectedPsiType, null))
        }
    }

    private fun processBinaryRelationExpression(
        expr: KtBinaryExpression, relation: RelationType,
        forceEqualityByContent: Boolean
    ) {
        val left = expr.left
        val right = expr.right
        val leftType = left?.getKotlinType()
        val rightType = right?.getKotlinType()
        val balancedType: KotlinType?
        if (forceEqualityByContent && leftType != null && rightType != null) {
            if (leftType.isMarkedNullable && !rightType.isMarkedNullable && leftType.makeNotNullable() == rightType) {
                balancedType = leftType
            } else if (rightType.isMarkedNullable && !leftType.isMarkedNullable && rightType.makeNotNullable() == leftType) {
                balancedType = rightType
            } else {
                balancedType = null
            }
        } else {
            balancedType = balanceType(leftType, rightType)
        }
        processExpression(left)
        addImplicitConversion(left, balancedType)
        processExpression(right)
        addImplicitConversion(right, balancedType)
        if (left == null || right == null) {
            addInstruction(EvalUnknownInstruction(KotlinExpressionAnchor(expr), 2))
            return
        }
        // TODO: support overloaded operators
        // TODO: avoid equals-comparison of unknown object types
        addInstruction(BooleanBinaryInstruction(relation, forceEqualityByContent, KotlinExpressionAnchor(expr)))
    }

    private fun balanceType(left: KotlinType?, right: KotlinType?): KotlinType? {
        if (left == null || right == null) return null
        if (left == right) return left
        if (left.canBeNull() && !right.canBeNull()) {
            return balanceType(left.makeNotNullable(), right)
        }
        if (!left.canBeNull() && right.canBeNull()) {
            return balanceType(left, right.makeNotNullable())
        }
        if (left.isDouble()) return left
        if (right.isDouble()) return right
        if (left.isFloat()) return left
        if (right.isFloat()) return right
        if (left.isLong()) return left
        if (right.isLong()) return right
        // The 'null' means no balancing is necessary
        return null
    }

    private fun addInstruction(inst: Instruction) {
        flow.addInstruction(inst)
    }

    private fun setOffset(offset: DeferredOffset) {
        offset.setOffset(flow.instructionCount)
    }

    private fun processWhenExpression(expr: KtWhenExpression) {
        if (expr.subjectExpression != null || expr.subjectVariable != null) {
            // TODO: support subjects
            broken = true
            return
        }
        val endOffset = DeferredOffset()
        for (entry in expr.entries) {
            if (entry.isElse) {
                processExpression(entry.expression)
                addInstruction(GotoInstruction(endOffset))
            } else {
                val branchStart = DeferredOffset()
                for (condition in entry.conditions) {
                    processWhenCondition(expr, condition)
                    addInstruction(ConditionalGotoInstruction(branchStart, DfTypes.TRUE))
                }
                val skipBranch = DeferredOffset()
                addInstruction(GotoInstruction(skipBranch))
                setOffset(branchStart)
                processExpression(entry.expression)
                addInstruction(GotoInstruction(endOffset))
                setOffset(skipBranch)
            }
        }
        pushUnknown()
        setOffset(endOffset)
        addInstruction(FinishElementInstruction(expr))
    }

    private fun processWhenCondition(expr: KtWhenExpression, condition: KtWhenCondition) {
        when (condition) {
            is KtWhenConditionWithExpression ->
                // TODO: implement comparison to subject
                processExpression(condition.expression)
            else ->
                broken = true // TODO: implement isPattern and inRange
        }
    }

    private fun processIfExpression(ifExpression: KtIfExpression) {
        val condition = ifExpression.condition
        processExpression(condition)
        val skipThenOffset = DeferredOffset()
        val thenStatement = ifExpression.then
        val elseStatement = ifExpression.`else`
        addInstruction(ConditionalGotoInstruction(skipThenOffset, DfTypes.FALSE, condition))
        addInstruction(FinishElementInstruction(null))
        processExpression(thenStatement)

        val skipElseOffset = DeferredOffset()
        addInstruction(GotoInstruction(skipElseOffset))
        setOffset(skipThenOffset)
        addInstruction(FinishElementInstruction(null))
        processExpression(elseStatement)
        setOffset(skipElseOffset)
        addInstruction(FinishElementInstruction(ifExpression))
    }
    
    companion object {
        private val LOG = logger<KtControlFlowBuilder>()
        private val ASSIGNMENT_TOKENS = TokenSet.create(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)
        private val totalCount = AtomicInteger()
        private val successCount = AtomicInteger()
    }
}