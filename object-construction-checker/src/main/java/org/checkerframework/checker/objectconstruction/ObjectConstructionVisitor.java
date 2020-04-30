package org.checkerframework.checker.objectconstruction;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.checker.objectconstruction.framework.FrameworkSupport;
import org.checkerframework.checker.objectconstruction.qual.AlwaysCall;
import org.checkerframework.checker.objectconstruction.qual.CalledMethods;
import org.checkerframework.checker.objectconstruction.qual.CalledMethodsPredicate;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.framework.source.Result;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;
import org.springframework.expression.spel.SpelParseException;

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

    TreePath currentPath = this.getCurrentPath();
    Tree parentNode = currentPath.getParentPath().getLeaf();
    if (parentNode instanceof ExpressionStatementTree) { // getAssignmentContex
      ExecutableElement element = TreeUtils.elementFromUse(node);
      TypeMirror returnType = element.getReturnType();
      TypeElement eType = TypesUtils.getTypeElement(returnType);
      AnnotationMirror alwaysCallAnno = atypeFactory.getDeclAnnotation(eType, AlwaysCall.class);
      if (alwaysCallAnno != null) {
        String alwaysCallAnnoVal =
            AnnotationUtils.getElementValue(alwaysCallAnno, "value", String.class, false);
        ;
        AnnotationMirror calledMethodAnno = null;
        for (AnnotationMirror annotationMirror : returnType.getAnnotationMirrors()) {
          if (AnnotationUtils.areSameByClass(annotationMirror, CalledMethods.class)) {
            calledMethodAnno = annotationMirror;
            break;
          }
        }
        if (calledMethodAnno != null) {
          List<String> calledMethodAnnoVal =
              AnnotationUtils.getElementValueArray(calledMethodAnno, "value", String.class, false);
          if (!calledMethodAnnoVal.contains(alwaysCallAnnoVal)) {
            checker.report(
                Result.failure(
                    "calledMethod doesn't contain alwaysCall obligations", element.getSimpleName()),
                node);
          }
        } else {
          checker.report(
              Result.failure("calledMethod doesn't exists", element.getSimpleName()), node);
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
}
