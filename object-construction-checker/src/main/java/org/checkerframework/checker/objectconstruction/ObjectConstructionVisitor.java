package org.checkerframework.checker.objectconstruction;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.objectconstruction.framework.FrameworkSupport;
import org.checkerframework.checker.objectconstruction.qual.AlwaysCall;
import org.checkerframework.checker.objectconstruction.qual.CalledMethodsPredicate;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.framework.source.Result;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;
import org.springframework.expression.spel.SpelParseException;

import static org.checkerframework.javacutil.TreeUtils.*;

public class ObjectConstructionVisitor
    extends BaseTypeVisitor<ObjectConstructionAnnotatedTypeFactory> {
  /** @param checker the type-checker associated with this visitor */
  public ObjectConstructionVisitor(final BaseTypeChecker checker) {
    super(checker);
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
  public Void visitMethodInvocation(MethodInvocationTree node, Void p) {

    if (isExpressionStatementTree() && isGoingOutOfScope(node)) {
      ExecutableElement element = TreeUtils.elementFromUse(node);
      TypeMirror returnType = element.getReturnType();
      TypeElement eType = TypesUtils.getTypeElement(returnType);
      AnnotationMirror alwaysCallAnno = atypeFactory.getDeclAnnotation(eType, AlwaysCall.class);
      if (alwaysCallAnno != null) {
        String alwaysCallAnnoVal =
            AnnotationUtils.getElementValue(alwaysCallAnno, "value", String.class, false);
        List<String> currentCalledMethods = getCalledMethodAnnotaion(node);
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

  public List<String> getCalledMethodAnnotaion(MethodInvocationTree node){
    AnnotationMirror calledMethodAnno = null;
    AnnotatedTypeMirror currentType = getTypeFactory().getAnnotatedType(node);
    if (currentType == null || !currentType.isAnnotatedInHierarchy(atypeFactory.TOP)) {
      calledMethodAnno = atypeFactory.TOP;
    } else {
      calledMethodAnno = currentType.getAnnotationInHierarchy(atypeFactory.TOP);
    }
    List<String> currentCalledMethods =
            ObjectConstructionAnnotatedTypeFactory.getValueOfAnnotationWithStringArgument(calledMethodAnno);
    return currentCalledMethods;
  }

  public boolean isExpressionStatementTree(){
    TreePath currentPath = this.getCurrentPath();
    Tree parentNode = currentPath.getParentPath().getLeaf();
    if (parentNode instanceof ExpressionStatementTree){
      return true;
    }else{
      return false;
    }
  }

  public boolean isGoingOutOfScope(MethodInvocationTree node){
    ExpressionTree receiver = TreeUtils.getReceiverTree(node);
    if(receiver == null)
      return true;
    while(TreeUtils.getReceiverTree(receiver)!=null){
      receiver = TreeUtils.getReceiverTree(receiver);
    }
    if(receiver instanceof MethodInvocationTree){
      return true;
    }else{
      return false;
    }
  }

}



