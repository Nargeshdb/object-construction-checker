package org.checkerframework.checker.objectconstruction;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.checker.objectconstruction.framework.FrameworkSupport;
import org.checkerframework.checker.objectconstruction.qual.AlwaysCall;
import org.checkerframework.checker.objectconstruction.qual.CalledMethodsPredicate;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.dataflow.analysis.Store;
import org.checkerframework.framework.source.Result;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;
import org.springframework.expression.spel.SpelParseException;

public class ObjectConstructionVisitor
    extends BaseTypeVisitor<ObjectConstructionAnnotatedTypeFactory> {
  private Map<MethodTree, LinkedList<Element>> methodVariables;

  /** @param checker the type-checker associated with this visitor */
  public ObjectConstructionVisitor(final BaseTypeChecker checker) {
    super(checker);
    methodVariables = new HashMap<>();
  }

  /** Checks each @CalledMethodsPredicate annotation to make sure the predicate is well-formed. */
  @Override
  public Void visitAnnotation(final AnnotationTree node, final Void p) {
    AnnotationMirror anno = TreeUtils.annotationFromAnnotationTree(node);
    if (AnnotationUtils.areSameByClass(anno, CalledMethodsPredicate.class)) {
      String predicate = AnnotationUtils.getElementValue(anno, "value", String.class, false);

      try {
        new CalledMethodsPredicateEvaluator(Collections.emptyList()).evaluate(predicate);
      } catch (SpelParseException e) {
        checker.report(Result.failure("predicate.invalid", e.getMessage()), node);
        return null;
      }
    }
    return super.visitAnnotation(node, p);
  }

  @Override
  public Void visitVariable(VariableTree node, Void o) {
    TreePath path = this.getCurrentPath();
    Tree enclosingTree = TreeUtils.enclosingOfKind(path, Tree.Kind.METHOD);
    if (enclosingTree != null && enclosingTree.getKind() == Tree.Kind.METHOD) {
      Element element = TreeUtils.elementFromDeclaration(node);
      TypeMirror type = element.asType();
      TypeElement eType = TypesUtils.getTypeElement(type);
      if (hasAlwaysCall(eType)) {
        methodVariables.get(enclosingTree).add(element);
      }
    }
    return super.visitVariable(node, o);
  }

  @Override
  public Void visitMethod(MethodTree node, Void o) {
    if (methodVariables.get(node) == null) {
      methodVariables.put(node, new LinkedList<>());
    }
    Store st = atypeFactory.getRegularExitStore(node);

    String t = "salam";
    return super.visitMethod(node, o);
  }

  @Override
  public Void visitMethodInvocation(MethodInvocationTree node, Void p) {

    if (!isAssignedToLocal(this.getCurrentPath())) {
      ExecutableElement element = TreeUtils.elementFromUse(node);
      TypeMirror returnType = element.getReturnType();
      TypeElement eType = TypesUtils.getTypeElement(returnType);
      if (hasAlwaysCall(eType)) {
        AnnotationMirror alwaysCallAnno = atypeFactory.getDeclAnnotation(eType, AlwaysCall.class);
        String alwaysCallAnnoVal =
            AnnotationUtils.getElementValue(alwaysCallAnno, "value", String.class, false);
        List<String> currentCalledMethods = getCalledMethodAnnotation(node);
        if (!currentCalledMethods.contains(alwaysCallAnnoVal)) {
          checker.report(
              Result.failure(
                  "calledMethod doesn't contain alwaysCall obligations", element.getSimpleName()),
              node);
        }
      }
    }

    if (checker.getBooleanOption(ObjectConstructionChecker.COUNT_FRAMEWORK_BUILD_CALLS)) {
      ExecutableElement element = TreeUtils.elementFromUse(node);
      for (FrameworkSupport frameworkSupport : getTypeFactory().getFrameworkSupports()) {
        if (frameworkSupport.isBuilderBuildMethod(element)) {
          ((ObjectConstructionChecker) checker).numBuildCalls++;
          break;
        }
      }
    }
    return super.visitMethodInvocation(node, p);
  }

  public List<String> getCalledMethodAnnotation(MethodInvocationTree node) {
    AnnotationMirror calledMethodAnno;
    AnnotatedTypeMirror currentType = getTypeFactory().getAnnotatedType(node);
    if (currentType == null || !currentType.isAnnotatedInHierarchy(atypeFactory.TOP)) {
      calledMethodAnno = atypeFactory.TOP;
    } else {
      calledMethodAnno = currentType.getAnnotationInHierarchy(atypeFactory.TOP);
    }
    List<String> currentCalledMethods =
        ObjectConstructionAnnotatedTypeFactory.getValueOfAnnotationWithStringArgument(
            calledMethodAnno);
    return currentCalledMethods;
  }

  public boolean isExpressionStatementTree() {
    TreePath currentPath = this.getCurrentPath();
    Tree parentNode = currentPath.getParentPath().getLeaf();
    if (parentNode instanceof ExpressionStatementTree) {
      return true;
    } else {
      return false;
    }
  }

  public ExpressionTree getReceiver(MethodInvocationTree node) {
    ExpressionTree receiver = TreeUtils.getReceiverTree(node);
    if (receiver == null) return receiver;
    while (TreeUtils.getReceiverTree(receiver) != null) {
      receiver = TreeUtils.getReceiverTree(receiver);
    }
    return receiver;
  }

  public boolean isGoingOutOfScope(MethodInvocationTree node) {
    ExpressionTree receiver = TreeUtils.getReceiverTree(node);
    if (receiver == null) return true;
    while (TreeUtils.getReceiverTree(receiver) != null) {
      receiver = TreeUtils.getReceiverTree(receiver);
    }
    if (receiver instanceof MethodInvocationTree) {
      return true;
    } else {
      return false;
    }
  }

  public static boolean isAssignedToLocal(final TreePath treePath) {
    TreePath parentPath = treePath.getParentPath();

    if (parentPath == null) {
      return false;
    }

    Tree parent = parentPath.getLeaf();
    switch (parent.getKind()) {
      case PARENTHESIZED:
        return isAssignedToLocal(parentPath);
      case CONDITIONAL_EXPRESSION:
        ConditionalExpressionTree cet = (ConditionalExpressionTree) parent;
        if (cet.getCondition() == treePath.getLeaf()) {
          // The assignment context for the condition is simply boolean.
          // No point in going on.
          return false;
        }
        // Otherwise use the context of the ConditionalExpressionTree.
        return isAssignedToLocal(parentPath);
      case ASSIGNMENT: // check if the left hand is a local variable
      case RETURN:
      case VARIABLE:
        return true;
      default:
        return false;
    }
  }

  public boolean hasAlwaysCall(TypeElement type) {
    if (type != null && atypeFactory.getDeclAnnotation(type, AlwaysCall.class) != null) {
      return true;
    } else {
      return false;
    }
  }

  //  private class CollectAlwaysCallLocalsVisitor extends TreePathScanner {
  //
  //    MethodTree mtree;
  //    Object o;
  //
  //    List<VariableElement> AllVarriable;
  //
  //    public CollectAlwaysCallLocalsVisitor(){
  //
  //    }
  //
  //    public void addVariable(MethodTree methodtree, VariableTree variableTree){
  //
  //    }
  //
  //
  //
  //    public void getAllVariable(){
  //
  //    }
  //
  //    public void hasAlwaysCall(){
  //
  //    }
  //  }

}
