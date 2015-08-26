/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.pyi;

import com.google.common.collect.ImmutableSet;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import com.intellij.util.Processor;
import com.jetbrains.python.codeInsight.controlflow.ScopeOwner;
import com.jetbrains.python.codeInsight.dataflow.scope.ScopeUtil;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.types.PyType;
import com.jetbrains.python.psi.types.PyTypeProviderBase;
import com.jetbrains.python.psi.types.PyUnionType;
import com.jetbrains.python.psi.types.TypeEvalContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * @author vlan
 */
public class PyiTypeProvider extends PyTypeProviderBase {
  @Override
  public Ref<PyType> getParameterType(@NotNull PyNamedParameter param, @NotNull PyFunction func, @NotNull TypeEvalContext context) {
    final String name = param.getName();
    if (name != null) {
      final PsiElement pythonStub = PyiUtil.getPythonStub(func);
      if (pythonStub instanceof PyFunction) {
        final PyFunction functionStub = (PyFunction)pythonStub;
        final PyNamedParameter paramSkeleton = functionStub.getParameterList().findParameterByName(name);
        if (paramSkeleton != null) {
          final PyType type = context.getType(paramSkeleton);
          if (type != null) {
            return Ref.create(type);
          }
        }
      }
      // TODO: Allow the stub for a function to be defined as a class or a target expression alias
    }
    return null;
  }

  @Nullable
  @Override
  public Ref<PyType> getReturnType(@NotNull PyCallable callable, @NotNull TypeEvalContext context) {
    final PsiElement pythonStub = PyiUtil.getPythonStub(callable);
    if (pythonStub instanceof PyCallable) {
      final PyType type = context.getReturnType((PyCallable)pythonStub);
      if (type != null) {
        return Ref.create(type);
      }
    }
    return null;
  }

  @Nullable
  @Override
  public PyType getCallableType(@NotNull PyCallable callable, @NotNull final TypeEvalContext context) {
    final PsiElement pythonStub = PyiUtil.getPythonStub(callable);
    if (pythonStub instanceof PyFunction) {
      final PyFunction functionStub = (PyFunction)pythonStub;
      if (isOverload(functionStub, context)) {
        return getOverloadType(functionStub, context);
      }
      return context.getType(functionStub);
    }
    return null;
  }

  @Override
  public PyType getReferenceType(@NotNull PsiElement target, TypeEvalContext context, @Nullable PsiElement anchor) {
    if (target instanceof PyTargetExpression) {
      final PsiElement pythonStub = PyiUtil.getPythonStub((PyTargetExpression)target);
      if (pythonStub instanceof PyTypedElement) {
        // XXX: Force the switch to AST for getting the type out of the hint in the comment
        final TypeEvalContext effectiveContext = context.maySwitchToAST(pythonStub) ?
                                                 context : TypeEvalContext.deepCodeInsight(target.getProject());
        return effectiveContext.getType((PyTypedElement)pythonStub);
      }
    }
    return null;
  }

  @Nullable
  private static PyType getOverloadType(@NotNull PyFunction function, @NotNull final TypeEvalContext context) {
    final ScopeOwner owner = ScopeUtil.getScopeOwner(function);
    final String name = function.getName();
    final List<PyFunction> overloads = new ArrayList<PyFunction>();
    final Processor<PyFunction> overloadsProcessor = new Processor<PyFunction>() {
      @Override
      public boolean process(@NotNull PyFunction f) {
        if (name != null && name.equals(f.getName()) && isOverload(f, context)) {
          overloads.add(f);
        }
        return true;
      }
    };
    if (owner instanceof PyClass) {
      final PyClass cls = (PyClass)owner;
      if (name != null) {
        cls.visitMethods(overloadsProcessor, false);
      }
    }
    else if (owner instanceof PyFile) {
      final PyFile file = (PyFile)owner;
      for (PyFunction f : file.getTopLevelFunctions()) {
        if (!overloadsProcessor.process(f)) {
          break;
        }
      }
    }
    if (!overloads.isEmpty()) {
      final List<PyType> overloadTypes = new ArrayList<PyType>();
      for (PyFunction overload : overloads) {
        overloadTypes.add(context.getType(overload));
      }
      return PyUnionType.union(overloadTypes);
    }
    return null;
  }

  private static boolean isOverload(@NotNull PyCallable callable, @NotNull TypeEvalContext context) {
    if (callable instanceof PyDecoratable) {
      final PyDecoratable decorated = (PyDecoratable)callable;
      final ImmutableSet<PyKnownDecoratorUtil.KnownDecorator> decorators =
        ImmutableSet.copyOf(PyKnownDecoratorUtil.getKnownDecorators(decorated, context));
      if (decorators.contains(PyKnownDecoratorUtil.KnownDecorator.TYPING_OVERLOAD)) {
        return true;
      }
    }
    return false;
  }
}
