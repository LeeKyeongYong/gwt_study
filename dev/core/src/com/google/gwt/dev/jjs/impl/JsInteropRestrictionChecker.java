/*
 * Copyright 2015 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.jjs.impl;

import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.dev.MinimalRebuildCache;
import com.google.gwt.dev.jjs.ast.Context;
import com.google.gwt.dev.jjs.ast.JClassType;
import com.google.gwt.dev.jjs.ast.JConstructor;
import com.google.gwt.dev.jjs.ast.JDeclarationStatement;
import com.google.gwt.dev.jjs.ast.JDeclaredType;
import com.google.gwt.dev.jjs.ast.JExpression;
import com.google.gwt.dev.jjs.ast.JExpressionStatement;
import com.google.gwt.dev.jjs.ast.JField;
import com.google.gwt.dev.jjs.ast.JInterfaceType;
import com.google.gwt.dev.jjs.ast.JMember;
import com.google.gwt.dev.jjs.ast.JMethod;
import com.google.gwt.dev.jjs.ast.JMethod.JsPropertyAccessorType;
import com.google.gwt.dev.jjs.ast.JMethodBody;
import com.google.gwt.dev.jjs.ast.JMethodCall;
import com.google.gwt.dev.jjs.ast.JPrimitiveType;
import com.google.gwt.dev.jjs.ast.JProgram;
import com.google.gwt.dev.jjs.ast.JStatement;
import com.google.gwt.dev.jjs.ast.JType;
import com.google.gwt.dev.jjs.ast.JVisitor;
import com.google.gwt.thirdparty.guava.common.base.Predicate;
import com.google.gwt.thirdparty.guava.common.collect.FluentIterable;
import com.google.gwt.thirdparty.guava.common.collect.Iterables;
import com.google.gwt.thirdparty.guava.common.collect.Maps;
import com.google.gwt.thirdparty.guava.common.collect.Sets;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks and throws errors for invalid JsInterop constructs.
 */
// TODO: handle custom JsType field/method names when that feature exists.
// TODO: move JsInterop checks from JSORestrictionsChecker to here.
// TODO: provide more information in global name collisions as it could be difficult to pinpoint in
// big projects.
public class JsInteropRestrictionChecker {

  public static void exec(TreeLogger logger, JProgram jprogram,
      MinimalRebuildCache minimalRebuildCache) throws UnableToCompleteException {
    JsInteropRestrictionChecker jsInteropRestrictionChecker =
        new JsInteropRestrictionChecker(logger, jprogram, minimalRebuildCache);

    jsInteropRestrictionChecker.checkProgram(jprogram);
    if (jsInteropRestrictionChecker.hasErrors) {
      throw new UnableToCompleteException();
    }
  }

  private Map<String, String> currentJsTypeMethodNameByGetterNames;
  private Map<String, String> currentJsTypeMethodNameByMemberNames;
  private Map<String, String> currentJsTypeMethodNameBySetterNames;
  private Set<JMethod> currentJsTypeProcessedMethods;
  private Map<String, JType> currentJsTypePropertyTypeByName;
  private JDeclaredType currentType;
  private boolean hasErrors;
  private final JProgram jprogram;
  private final TreeLogger logger;
  private final MinimalRebuildCache minimalRebuildCache;

  private JsInteropRestrictionChecker(TreeLogger logger, JProgram jprogram,
      MinimalRebuildCache minimalRebuildCache) {
    this.logger = logger;
    this.jprogram = jprogram;
    this.minimalRebuildCache = minimalRebuildCache;
  }

  /**
   * Returns true if the constructor method is locally empty (allows calls to empty init and super
   * constructor).
   */
  private static boolean isConstructorEmpty(final JConstructor constructor) {
    List<JStatement> statements = FluentIterable
        .from(((JMethodBody) constructor.getBody()).getStatements())
        .filter(new Predicate<JStatement>() {
          @Override
          public boolean apply(JStatement statement) {
            JClassType type = constructor.getEnclosingType();
            if (isImplicitSuperCall(statement, type.getSuperClass())) {
              return false;
            }
            if (isEmptyInitCall(statement, type)) {
              return false;
            }
            if (statement instanceof JDeclarationStatement) {
              return ((JDeclarationStatement) statement).getInitializer() != null;
            }
            return true;
          }
        }).toList();
    return statements.isEmpty();
  }

  private static JMethodCall isMethodCall(JStatement statement) {
    if (!(statement instanceof JExpressionStatement)) {
      return null;
    }
    JExpression expression = ((JExpressionStatement) statement).getExpr();

    return expression instanceof JMethodCall ? (JMethodCall) expression : null;
  }

  private static boolean isEmptyInitCall(JStatement statement, JDeclaredType type) {
    JMethodCall methodCall = isMethodCall(statement);

    return methodCall != null
        && methodCall.getTarget() == type.getInitMethod()
        && ((JMethodBody) methodCall.getTarget().getBody()).getStatements().isEmpty();
  }

  private static boolean isImplicitSuperCall(JStatement statement, JDeclaredType superType) {
    JMethodCall methodCall = isMethodCall(statement);

    return methodCall != null
        && methodCall.isStaticDispatchOnly()
        && methodCall.getTarget().isConstructor()
        && methodCall.getTarget().getEnclosingType() == superType;
  }

  /**
   * Returns true if the clinit for a type is locally empty (except for the call to its super
   * clinit).
   */
  private static boolean isClinitEmpty(JDeclaredType type) {
    JMethod clinit = type.getClinitMethod();
    List<JStatement> statements = FluentIterable
        .from(((JMethodBody) clinit.getBody()).getStatements())
        .filter(new Predicate<JStatement>() {
          @Override
          public boolean apply(JStatement statement) {
            if (!(statement instanceof JDeclarationStatement)) {
              return true;
            }
            JDeclarationStatement declarationStatement = (JDeclarationStatement) statement;
            JField field = (JField) declarationStatement.getVariableRef().getTarget();
            return !field.isCompileTimeConstant();
          }
        }).toList();
    if (statements.isEmpty()) {
      return true;
    }
    return statements.size() == 1 && isClinitCall(statements.get(0), type.getSuperClass());
  }

  private static boolean isClinitCall(JStatement statement, JClassType superClass) {
    if (superClass == null || !(statement instanceof JExpressionStatement)) {
      return false;
    }

    JExpression expression = ((JExpressionStatement) statement).getExpr();
    if (!(expression instanceof JMethodCall)) {
      return false;
    }
    return ((JMethodCall) expression).getTarget() == superClass.getClinitMethod();
  }

  private void checkConstructors(JDeclaredType x) {
    List<JMethod> exportedCtors = FluentIterable
        .from(x.getMethods())
        .filter(new Predicate<JMethod>() {
          @Override
          public boolean apply(JMethod m) {
            return m.isJsConstructor();
          }
        }).toList();

    if (exportedCtors.isEmpty()) {
      return;
    }

    if (exportedCtors.size() > 1) {
      logError("More than one constructor exported for %s.", x.getName());
    }

    final JConstructor exportedCtor = (JConstructor) exportedCtors.get(0);
    if (!exportedCtor.getJsName().isEmpty()) {
      logError("Constructor '%s' cannot have an export name.", exportedCtor.getQualifiedName());
    }

    boolean anyNonDelegatingConstructor = Iterables.any(x.getMethods(), new Predicate<JMethod>() {
      @Override
      public boolean apply(JMethod method) {
        return method != exportedCtor && method instanceof JConstructor
            && !isDelegatingToConstructor((JConstructor) method, exportedCtor);
      }
    });

    if (anyNonDelegatingConstructor) {
      logError("Constructor '%s' can only be exported if all constructors in the class are "
          + "delegating to it.", exportedCtor.getQualifiedName());
    }
  }

  private boolean isDelegatingToConstructor(JConstructor ctor, JConstructor targetCtor) {
    List<JStatement> statements = ctor.getBody().getBlock().getStatements();
    JExpressionStatement statement = (JExpressionStatement) statements.get(0);
    JMethodCall call = (JMethodCall) statement.getExpr();
    assert call.isStaticDispatchOnly() : "Every ctor should either have this() or super() call";
    return call.getTarget().equals(targetCtor);
  }

  private void checkField(JField x) {
    if (x.isJsNative()) {
      checkNativeJsMember(x);
    }

    if (!x.isJsProperty()) {
      return;
    }

    if (x.needsDynamicDispatch()) {
      checkJsTypeFieldName(x, x.getJsName());
    } else if (!x.isJsNative() && currentType == x.getEnclosingType()) {
      checkExportName(x);
    }
  }

  private void checkMethod(JMethod x) {
    if (!currentJsTypeProcessedMethods.add(x)) {
      return;
    }
    currentJsTypeProcessedMethods.addAll(x.getOverriddenMethods());

    if (x.isJsNative()) {
      checkNativeJsMember(x);
    }

    if (!x.isOrOverridesJsMethod()) {
      return;
    }

    if (x.needsDynamicDispatch()) {
      checkJsTypeMethod(x);
    } else if (!x.isJsNative() && currentType == x.getEnclosingType()) {
      checkExportName(x);
    }

    if (currentType == x.getEnclosingType() && x.isJsPropertyAccessor() &&
        !currentType.isJsType()) {
      if (currentType instanceof JInterfaceType) {
        logError("Method '%s' can't be a JsProperty since interface '%s' is not a JsType.",
            x.getName(), x.getEnclosingType().getName());
      } else {
        logError("Method '%s' can't be a JsProperty since '%s' "
            + "is not an interface.", x.getName(), x.getEnclosingType().getName());
      }
    }
  }

  private void checkExportName(JMember x) {
    boolean success = minimalRebuildCache.addExportedGlobalName(x.getQualifiedJsName(),
        currentType.getName());
    if (!success) {
      logError("Member '%s' can't be exported because the global name '%s' is already taken.",
          x.getQualifiedName(), x.getQualifiedJsName());
    }
  }

  private void checkInconsistentPropertyType(String propertyName, String enclosingTypeName,
      JType parameterType) {
    JType recordedType = currentJsTypePropertyTypeByName.put(propertyName, parameterType);
    if (recordedType != null && recordedType != parameterType) {
      logError("The setter and getter for JsProperty '%s' in type '%s' must have consistent types.",
          propertyName, enclosingTypeName);
    }
  }

  private void checkJsTypeFieldName(JField field, String memberName) {
    boolean success =
        currentJsTypeMethodNameByMemberNames.put(memberName, field.getQualifiedName()) == null;
    if (!success) {
      logError("Field '%s' can't be exported in type '%s' because the member name "
          + "'%s' is already taken.", field.getQualifiedName(), currentType.getName(), memberName);
    }
  }

  private void checkNativeJsMember(JMember member) {
    if (member.isSynthetic()) {
      return;
    }

    if (member.getJsName() == null) {
      logError("Native JsType member '%s' is not public or has @JsNoExport.",
          member.getQualifiedName());
      return;
    }
  }

  private void checkJsTypeMethod(JMethod method) {
    if (method.isSynthetic() && !method.isForwarding()) {
      // A name slot taken up by a synthetic method, such as a bridge method for a generic method,
      // is not the fault of the user and so should not be reported as an error. JS generation
      // should take responsibility for ensuring that only the correct method version (in this
      // particular set of colliding method names) is exported. Forwarding synthetic methods
      // (such as an accidental override forwarding method that occurs when a JsType interface
      // starts exposing a method in class B that is only ever implemented in its parent class A)
      // though should be checked since they are exported and do take up an name slot.
      return;
    }

    String jsMemberName = method.getJsName();
    String qualifiedMethodName = method.getQualifiedName();
    String typeName = method.getEnclosingType().getName();
    JsPropertyAccessorType accessorType = method.getJsPropertyAccessorType();

    if (jsMemberName == null) {
      logError("'%s' can't be exported because the method overloads multiple methods with "
          + "different names.", qualifiedMethodName);
    }

    if (accessorType == JsPropertyAccessorType.GETTER) {
      if (!method.getParams().isEmpty() || method.getType() == JPrimitiveType.VOID) {
        logError("There can't be void return type or any parameters for the JsProperty getter"
            + " '%s'.", qualifiedMethodName);
        return;
      }
      if (method.getType() != JPrimitiveType.BOOLEAN && method.getName().startsWith("is")) {
        logError("There can't be non-boolean return for the JsProperty 'is' getter '%s'.",
            qualifiedMethodName);
        return;
      }
      if (currentJsTypeMethodNameByGetterNames.put(jsMemberName, qualifiedMethodName) != null) {
        // Don't allow multiple getters for the same property name.
        logError("There can't be more than one getter for JsProperty '%s' in type '%s'.",
            jsMemberName, typeName);
        return;
      }
      checkNameCollisionForGetterAndRegular(jsMemberName, typeName);
      checkInconsistentPropertyType(jsMemberName, typeName, method.getOriginalReturnType());
    } else if (accessorType == JsPropertyAccessorType.SETTER) {
      if (method.getParams().size() != 1 || method.getType() != JPrimitiveType.VOID) {
        logError("There needs to be single parameter and void return type for the JsProperty setter"
            + " '%s'.", qualifiedMethodName);
        return;
      }
      if (currentJsTypeMethodNameBySetterNames.put(jsMemberName, qualifiedMethodName) != null) {
        // Don't allow multiple setters for the same property name.
        logError("There can't be more than one setter for JsProperty '%s' in type '%s'.",
            jsMemberName, typeName);
        return;
      }
      checkNameCollisionForSetterAndRegular(jsMemberName, typeName);
      checkInconsistentPropertyType(jsMemberName, typeName,
          Iterables.getOnlyElement(method.getParams()).getType());
    } else if (accessorType == JsPropertyAccessorType.UNDEFINED) {
      // We couldn't extract the JsPropertyType.
      logError("JsProperty '%s' doesn't follow Java Bean naming conventions.", qualifiedMethodName);
    } else {
      // If it's just an regular JsType method.
      if (currentJsTypeMethodNameByMemberNames.put(jsMemberName, qualifiedMethodName) != null) {
        logError("Method '%s' can't be exported in type '%s' because the member name "
            + "'%s' is already taken.", qualifiedMethodName, currentType.getName(), jsMemberName);
      }
      checkNameCollisionForGetterAndRegular(jsMemberName, typeName);
      checkNameCollisionForSetterAndRegular(jsMemberName, typeName);
    }
  }

  private void checkNameCollisionForGetterAndRegular(String getterName, String typeName) {
    if (currentJsTypeMethodNameByGetterNames.containsKey(getterName)
        && currentJsTypeMethodNameByMemberNames.containsKey(getterName)) {
      logError("The JsType member '%s' and JsProperty '%s' can't both be named "
              + "'%s' in type '%s'.", currentJsTypeMethodNameByMemberNames.get(getterName),
          currentJsTypeMethodNameByGetterNames.get(getterName), getterName, typeName);
    }
  }

  private void checkNameCollisionForSetterAndRegular(String setterName, String typeName) {
    if (currentJsTypeMethodNameBySetterNames.containsKey(setterName)
        && currentJsTypeMethodNameByMemberNames.containsKey(setterName)) {
      logError("The JsType member '%s' and JsProperty '%s' can't both be named "
              + "'%s' in type '%s'.", currentJsTypeMethodNameByMemberNames.get(setterName),
          currentJsTypeMethodNameBySetterNames.get(setterName), setterName, typeName);
    }
  }

  private void checkNoStaticJsPropertyCalls() {
    new JVisitor() {
      @Override
      public void endVisit(JMethodCall x, Context ctx) {
        JMethod target = x.getTarget();
        if (x.isStaticDispatchOnly() && target.isJsPropertyAccessor()) {
          logError("Cannot call property accessor '%s' via super (%s:%d).",
              target.getQualifiedName(),
              x.getSourceInfo().getFileName(),
              x.getSourceInfo().getStartLine());
        }
      }
    }.accept(jprogram);
  }

  private void checkNativeJsType(JDeclaredType type) {
    // TODO(rluble): add inheritance restrictions.
    if (type.isEnumOrSubclass() != null) {
      logError("Enum '%s' cannot be a native JsType.", type);
      return;
    }
    if (!isClinitEmpty(type)) {
      logError("Native JsType '%s' cannot have static initializer.", type);
    }

    for (JConstructor constructor : type.getConstructors()) {
      if (!isConstructorEmpty(constructor)) {
        logError("Native JsType constructor '%s' cannot have non-empty method body.",
            constructor.getQualifiedName());
      }
    }
  }

  private void checkJsFunction(JDeclaredType type) {
    if (!isClinitEmpty(type)) {
      logError("JsFunction '%s' cannot have static initializer.", type);
    }

    if (type.getImplements().size() > 0) {
      logError("JsFunction '%s' cannot extend other interfaces.", type);
    }

    if (type.isJsType()) {
      logError("'%s' cannot be both a JsFunction and a JsType at the same time.", type);
    }

    Set<String> subTypes = jprogram.typeOracle.getSubTypeNames(type.getName());
    if (!subTypes.isEmpty()) {
      logError("JsFunction '%s' cannot be extended by other interfaces:%s", type, subTypes);
    }
  }

  private void checkJsFunctionImplementation(JDeclaredType type) {
    if (type.getImplements().size() != 1) {
      logError("JsFunction implementation '%s' cannot implement more than one interface.", type);
    }

    if (type.isJsType()) {
      logError("'%s' cannot be both a JsFunction implementation and a JsType at the same time.",
          type);
    }

    if (type.getSuperClass() != jprogram.getTypeJavaLangObject()) {
      logError("JsFunction implementation '%s' cannot extend a class.", type);
    }

    Set<String> subTypes = jprogram.typeOracle.getSubTypeNames(type.getName());
    if (!subTypes.isEmpty()) {
      logError("Implementation of JsFunction '%s' cannot be extended by other classes:%s", type,
          subTypes);
    }
  }

  private void checkProgram(JProgram jprogram) {
    for (JDeclaredType type : jprogram.getModuleDeclaredTypes()) {
      checkType(type);
    }
    checkNoStaticJsPropertyCalls();
  }

  private void checkType(JDeclaredType type) {
    currentJsTypeProcessedMethods = Sets.newHashSet();
    currentJsTypePropertyTypeByName = Maps.newHashMap();
    currentJsTypeMethodNameByMemberNames = Maps.newHashMap();
    currentJsTypeMethodNameByGetterNames = Maps.newHashMap();
    currentJsTypeMethodNameBySetterNames = Maps.newHashMap();
    currentType = type;
    minimalRebuildCache.removeJsInteropNames(type.getName());

    if (type.isJsNative()) {
      checkNativeJsType(type);
    }

    if (type.isJsFunction()) {
      checkJsFunction(type);
    } else if (type.isJsFunctionImplementation()) {
      checkJsFunctionImplementation(type);
    } else {
      checkConstructors(type);
    }

    for (;type != null; type = type.getSuperClass())  {
      for (JField field : type.getFields()) {
        checkField(field);
      }
      for (JMethod method : type.getMethods()) {
        checkMethod(method);
      }
    }
  }

  private void logError(String format, JType type) {
    logError(format, type.getName());
  }

  private void logError(String format, JType type, Set<String> subTypes) {
    StringBuilder subTypeNames = new StringBuilder();
    for (String typeName : subTypes) {
      subTypeNames.append("\n\t").append(typeName);
    }
    logError(format, type.getName(), subTypeNames);
  }

  private void logError(String format, Object... args) {
    logger.log(TreeLogger.ERROR, String.format(format, args));
    hasErrors = true;
  }

  private void logWarning(String format, Object... args) {
    logger.log(TreeLogger.WARN, String.format(format, args));
  }
}
