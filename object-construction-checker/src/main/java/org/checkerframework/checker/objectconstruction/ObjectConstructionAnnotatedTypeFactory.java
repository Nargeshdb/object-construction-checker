package org.checkerframework.checker.objectconstruction;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import org.checkerframework.checker.builder.qual.ReturnsReceiver;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.objectconstruction.framework.AutoValueSupport;
import org.checkerframework.checker.objectconstruction.framework.FrameworkSupport;
import org.checkerframework.checker.objectconstruction.framework.FrameworkSupportUtils;
import org.checkerframework.checker.objectconstruction.framework.LombokSupport;
import org.checkerframework.checker.objectconstruction.qual.AlwaysCall;
import org.checkerframework.checker.objectconstruction.qual.CalledMethods;
import org.checkerframework.checker.objectconstruction.qual.CalledMethodsBottom;
import org.checkerframework.checker.objectconstruction.qual.CalledMethodsPredicate;
import org.checkerframework.checker.objectconstruction.qual.CalledMethodsTop;
import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.returnsreceiver.ReturnsReceiverAnnotatedTypeFactory;
import org.checkerframework.common.returnsreceiver.ReturnsReceiverChecker;
import org.checkerframework.common.returnsreceiver.qual.This;
import org.checkerframework.common.value.ValueAnnotatedTypeFactory;
import org.checkerframework.common.value.ValueChecker;
import org.checkerframework.common.value.qual.StringVal;
import org.checkerframework.dataflow.cfg.ControlFlowGraph;
import org.checkerframework.dataflow.cfg.block.Block;
import org.checkerframework.dataflow.cfg.block.BlockImpl;
import org.checkerframework.dataflow.cfg.block.ConditionalBlock;
import org.checkerframework.dataflow.cfg.block.ExceptionBlockImpl;
import org.checkerframework.dataflow.cfg.block.RegularBlockImpl;
import org.checkerframework.dataflow.cfg.block.SingleSuccessorBlock;
import org.checkerframework.dataflow.cfg.block.SpecialBlock;
import org.checkerframework.dataflow.cfg.block.SpecialBlockImpl;
import org.checkerframework.dataflow.cfg.node.AssignmentNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.MethodAccessNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.ObjectCreationNode;
import org.checkerframework.dataflow.cfg.node.ReturnNode;
import org.checkerframework.framework.flow.CFAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.source.DiagMessage;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.treeannotator.ListTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.TreeAnnotator;
import org.checkerframework.framework.type.typeannotator.ListTypeAnnotator;
import org.checkerframework.framework.type.typeannotator.TypeAnnotator;
import org.checkerframework.framework.util.MultiGraphQualifierHierarchy;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.Pair;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;

/**
 * The annotated type factory for the object construction checker. Primarily responsible for the
 * subtyping rules between @CalledMethod annotations.
 */
public class ObjectConstructionAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

  /** The top annotation. Package private to permit access from the Transfer class. */
  final AnnotationMirror TOP;

  /** The bottom annotation. Package private to permit access from the Transfer class. */
  final AnnotationMirror BOTTOM;

  private final ExecutableElement collectionsSingletonList;

  private final boolean useValueChecker;

  /** The collection of built-in framework support for the object construction checker. */
  private Collection<FrameworkSupport> frameworkSupports;

  /**
   * Lombok has a flag to generate @CalledMethods annotations, but they used the old package name,
   * so we maintain it as an alias.
   */
  private static final String OLD_CALLED_METHODS =
      "org.checkerframework.checker.builder.qual.CalledMethods";

  /**
   * Lombok also generates an @NotCalledMethods annotation, which we have no support for. We
   * therefore treat it as top.
   */
  private static final String OLD_NOT_CALLED_METHODS =
      "org.checkerframework.checker.builder.qual.NotCalledMethods";

  /**
   * Default constructor matching super. Should be called automatically.
   *
   * @param checker the checker associated with this type factory
   */
  public ObjectConstructionAnnotatedTypeFactory(final BaseTypeChecker checker) {
    super(checker);
    TOP = AnnotationBuilder.fromClass(elements, CalledMethodsTop.class);
    BOTTOM = AnnotationBuilder.fromClass(elements, CalledMethodsBottom.class);
    EnumSet<FrameworkSupportUtils.Framework> frameworkSet =
        FrameworkSupportUtils.getFrameworkSet(
            checker.getOption(ObjectConstructionChecker.DISABLED_FRAMEWORK_SUPPORTS));
    frameworkSupports = new ArrayList<FrameworkSupport>();

    for (FrameworkSupportUtils.Framework framework : frameworkSet) {
      switch (framework) {
        case AUTO_VALUE:
          frameworkSupports.add(new AutoValueSupport(this));
          break;
        case LOMBOK:
          frameworkSupports.add(new LombokSupport(this));
          break;
      }
    }

    this.useValueChecker = checker.hasOption(ObjectConstructionChecker.USE_VALUE_CHECKER);
    this.collectionsSingletonList =
        TreeUtils.getMethod("java.util.Collections", "singletonList", 1, getProcessingEnv());
    addAliasedAnnotation(OLD_CALLED_METHODS, CalledMethods.class, true);
    addAliasedAnnotation(OLD_NOT_CALLED_METHODS, TOP);
    this.postInit();
  }

  /**
   * Creates a @CalledMethods annotation whose values are the given strings.
   *
   * @param val the methods that have been called
   * @return an annotation indicating that the given methods have been called
   */
  public AnnotationMirror createCalledMethods(final String... val) {
    if (val.length == 0) {
      return TOP;
    }
    AnnotationBuilder builder = new AnnotationBuilder(processingEnv, CalledMethods.class);
    Arrays.sort(val);
    builder.setValue("value", val);
    return builder.build();
  }

  @Override
  public TreeAnnotator createTreeAnnotator() {
    return new ListTreeAnnotator(
        super.createTreeAnnotator(), new ObjectConstructionTreeAnnotator(this));
  }

  @Override
  protected TypeAnnotator createTypeAnnotator() {
    return new ListTypeAnnotator(
        super.createTypeAnnotator(), new ObjectConstructionTypeAnnotator(this));
  }

  @Override
  public QualifierHierarchy createQualifierHierarchy(
      final MultiGraphQualifierHierarchy.MultiGraphFactory factory) {
    return new ObjectConstructionQualifierHierarchy(factory);
  }

  private ReturnsReceiverAnnotatedTypeFactory getReturnsRcvrAnnotatedTypeFactory() {
    return getTypeFactoryOfSubchecker(ReturnsReceiverChecker.class);
  }

  /**
   * Returns whether the return type of the given method invocation tree has an @This annotation
   * from the Returns Receiver Checker.
   *
   * <p>Package-private to permit calls from {@link ObjectConstructionTransfer}.
   */
  boolean returnsThis(final MethodInvocationTree tree) {
    ReturnsReceiverAnnotatedTypeFactory rrATF = getReturnsRcvrAnnotatedTypeFactory();
    ExecutableElement methodEle = TreeUtils.elementFromUse(tree);
    AnnotatedTypeMirror methodATm = rrATF.getAnnotatedType(methodEle);
    AnnotatedTypeMirror rrType =
        ((AnnotatedTypeMirror.AnnotatedExecutableType) methodATm).getReturnType();
    return (rrType != null && rrType.hasAnnotation(This.class))
        || hasOldReturnsReceiverAnnotation(tree);
  }

  /**
   * Continue to trust but not check the old {@link
   * org.checkerframework.checker.builder.qual.ReturnsReceiver} annotation, for
   * backwards-compatibility.
   */
  private boolean hasOldReturnsReceiverAnnotation(MethodInvocationTree tree) {
    return this.getDeclAnnotation(TreeUtils.elementFromUse(tree), ReturnsReceiver.class) != null;
  }

  /**
   * Given a tree, returns the method that the tree should be considered as calling. Returns
   * "withOwners" if the call sets an "owner", "owner-alias", or "owner-id" filter. Returns
   * "withImageIds" if the call sets an "image-ids" filter.
   *
   * <p>Package-private to permit calls from {@link ObjectConstructionTransfer}.
   *
   * @return either the first argument, or "withOwners" or "withImageIds" if the tree is an
   *     equivalent filter addition.
   */
  String adjustMethodNameUsingValueChecker(
      final String methodName, final MethodInvocationTree tree) {
    if (!useValueChecker) {
      return methodName;
    }

    ExecutableElement invokedMethod = TreeUtils.elementFromUse(tree);
    if (!"com.amazonaws.services.ec2.model.DescribeImagesRequest"
        .equals(ElementUtils.enclosingClass(invokedMethod).getQualifiedName().toString())) {
      return methodName;
    }

    if ("withFilters".equals(methodName) || "setFilters".equals(methodName)) {
      for (Tree filterTree : tree.getArguments()) {
        // Search the arguments to withFilters for a Filter constructor invocation,
        // passing through as many method invocation trees as needed. This code is searching
        // for code of the form:
        // new Filter("owner").withValues("...")
        // or code of the form:
        // new Filter().*.withName("owner").*

        // Set to non-null iff a call to withName was observed; in that case, this variable's
        // value is the argument to withName.
        String withNameArg = null;
        ValueAnnotatedTypeFactory valueATF = getTypeFactoryOfSubchecker(ValueChecker.class);

        while (filterTree != null && filterTree.getKind() == Tree.Kind.METHOD_INVOCATION) {

          MethodInvocationTree filterTreeAsMethodInvocation = (MethodInvocationTree) filterTree;
          String filterMethodName = TreeUtils.methodName(filterTreeAsMethodInvocation).toString();
          if ("withName".equals(filterMethodName)
              && filterTreeAsMethodInvocation.getArguments().size() >= 1) {
            Tree withNameArgTree = filterTreeAsMethodInvocation.getArguments().get(0);
            withNameArg = getExactStringValue(withNameArgTree, valueATF);
          }

          // Descend into a call to Collections.singletonList()
          if (TreeUtils.isMethodInvocation(
              filterTree, collectionsSingletonList, getProcessingEnv())) {
            filterTree = filterTreeAsMethodInvocation.getArguments().get(0);
          } else {
            filterTree = TreeUtils.getReceiverTree(filterTreeAsMethodInvocation.getMethodSelect());
          }
        }
        if (filterTree == null) {
          continue;
        }
        if (filterTree.getKind() == Tree.Kind.NEW_CLASS) {

          String value;
          if (withNameArg != null) {
            value = withNameArg;
          } else {
            ExpressionTree constructorArg = ((NewClassTree) filterTree).getArguments().get(0);
            value = getExactStringValue(constructorArg, valueATF);
          }

          if (value != null) {
            switch (value) {
              case "owner":
              case "owner-alias":
              case "owner-id":
                return "withOwners";
              case "image-id":
                return "withImageIds";
              default:
            }
          }
        }
      }
    }
    return methodName;
  }

  // Once https://github.com/typetools/checker-framework/pull/2726 is merged
  // and we update to the release with that code, this method should
  // be replaced with a call to ValueCheckerUtils#getExactStringValue().
  private static String getExactStringValue(Tree tree, ValueAnnotatedTypeFactory factory) {
    AnnotatedTypeMirror valueType = factory.getAnnotatedType(tree);
    if (valueType.hasAnnotation(StringVal.class)) {
      AnnotationMirror valueAnno = valueType.getAnnotation(StringVal.class);
      List<String> possibleValues = getValueOfAnnotationWithStringArgument(valueAnno);
      if (possibleValues.size() == 1) {
        return possibleValues.get(0);
      }
    }
    return null;
  }

  @Override
  public void postAnalyze(ControlFlowGraph cfg) {
    checkCFG(cfg.getEntryBlock(), cfg);
    super.postAnalyze(cfg);
  }

  private void checkCFG(SpecialBlock entry, ControlFlowGraph cfg) {

    Set<Pair<Long, Set<AssignmentNode>>> visited = new HashSet<>();
    visited.add(Pair.of(entry.getId(), Collections.emptySet()));

    Deque<Pair<BlockImpl, Set<AssignmentNode>>> worklist = new ArrayDeque<>();
    worklist.add(Pair.of((BlockImpl) entry, Collections.emptySet()));

    while (!worklist.isEmpty()) {

      Pair<BlockImpl, Set<AssignmentNode>> state = worklist.getLast();
      worklist.removeLast();
      List<Node> nodes = getBlockNodes(state.first);
      Set<AssignmentNode> newDefs = new HashSet<>(state.second);

      for (Node node : nodes) {
        if (node instanceof AssignmentNode) {
          Node lhs = ((AssignmentNode) node).getTarget();
          Node rhs = ((AssignmentNode) node).getExpression();

          if (lhs instanceof LocalVariableNode && hasAlwaysCall(lhs.getType())) {

            // Reassignment to the lhs
            if (isVarInPrevDefs(newDefs, (LocalVariableNode) lhs)) {
              AssignmentNode latestAssignmentNode =
                  getAssignmentNodeOfVar(newDefs, (LocalVariableNode) lhs);
              reportAlwaysCallErrors(latestAssignmentNode, getStoreBefore(node), null);
              newDefs.remove(latestAssignmentNode);
            }

            // If the rhs is ObjectCreationNode, MethodInvocationNode, MethodAccessNode or
            // LocalVariableNode that exists in the newDefs (Note that if the localVariableNode
            // exists in the newDefs it means it is assigned to a non-null values), then it adds
            // the the AssignmentNode to the newDefs.
            if ((rhs instanceof ObjectCreationNode)
                || (rhs instanceof MethodInvocationNode)
                || (rhs instanceof MethodAccessNode)
                || (rhs instanceof LocalVariableNode
                    && isVarInPrevDefs(newDefs, (LocalVariableNode) rhs))) {
              newDefs.add((AssignmentNode) node);
            }

            // Ownership Transfer
            if (rhs instanceof LocalVariableNode
                && isVarInPrevDefs(newDefs, (LocalVariableNode) rhs)) {
              newDefs.remove(getAssignmentNodeOfVar(newDefs, (LocalVariableNode) rhs));
            }
          }
        }

        // Remove the returned localVariableNode from newDefs.
        if (node instanceof ReturnNode) {
          Node result = ((ReturnNode) node).getResult();
          if (result instanceof LocalVariableNode
              && isVarInPrevDefs(newDefs, (LocalVariableNode) result)) {
            newDefs.remove(getAssignmentNodeOfVar(newDefs, (LocalVariableNode) result));
          }
        }
      }

      for (BlockImpl succ : getSuccessors(state.first)) {
        Set<AssignmentNode> toRemove = new HashSet<>();

        // There is nothing needs to be checked
        if (newDefs.size() == 0) {
          propagate(Pair.of(succ, newDefs), visited, worklist);
          continue;
        }

        CFStore succRegularStore = getAnalysis().getInput(succ).getRegularStore();

        for (AssignmentNode assignmentNode : newDefs) {

          // If the successor block is the exit block or if the set of the pair of block id and set
          // of defs is repeated in a loop or if the variable is going out of scope
          if (succ instanceof SpecialBlockImpl
              || visited.contains(Pair.of(succ.getId(), newDefs)) || succRegularStore.getValue((LocalVariableNode) (assignmentNode.getTarget()))
                  == null) {

            if (nodes.size() == 0) { // If the cur block is special or conditional block
              reportAlwaysCallErrors(assignmentNode, succRegularStore, null);

            } else { // If the cur block is Exception/Regular block then it checks AlwaysCall
              // condition in the store right after the last node
              Node last = nodes.get(nodes.size() - 1);
              CFStore storeAfter = getStoreAfter(last);
              reportAlwaysCallErrors(
                  assignmentNode,
                  storeAfter,
                  (last instanceof AssignmentNode) ? getAnnotatedType(last.getTree()) : null);
            }

          }

          if (succRegularStore.getValue((LocalVariableNode) (assignmentNode.getTarget()))
              == null) {
            // If the lhs of the assignmentNode isn't in the successor's input store, it means the variable is going out of scope
            toRemove.add(assignmentNode);
          }
        }

        newDefs.removeAll(toRemove);
        propagate(Pair.of(succ, newDefs), visited, worklist);
      }
    }
  }

  /**
   * it inputs a set of AssignmentNodes(defs) and a LocalVariableNode(localVariableNode), if there
   * is an AssignmentNode in defs that its lhs is equal to localVarNode, then it returns that
   * AssignmentNode, otherwise it returns null.
   *
   * @param defs
   * @param localVarNode
   * @return
   */
  private AssignmentNode getAssignmentNodeOfVar(
      Set<AssignmentNode> defs, LocalVariableNode localVarNode) {

    for (AssignmentNode assignmentNode : defs) {
      LocalVariableNode lhs = (LocalVariableNode) assignmentNode.getTarget();

      if (lhs.getElement() == localVarNode.getElement()) {
        return assignmentNode;
      }
    }

    return null;
  }

  private boolean isVarInPrevDefs(Set<AssignmentNode> defs, LocalVariableNode node) {

    for (AssignmentNode assignmentNode : defs) {
      LocalVariableNode lhs = (LocalVariableNode) (assignmentNode).getTarget();
      if (lhs.getElement() == (node).getElement()) {
        return true;
      }
    }
    return false;
  }

  /**
   * If cur is Conditional block, then it returns a list of two successor blocks contains then block
   * and else block. If cur is instance of SingleSuccessorBlock then it returns a list of one block.
   * Otherwise, it throws an assertion error at runtime.
   *
   * @param cur
   * @return list of successor blocks
   */
  private List<BlockImpl> getSuccessors(BlockImpl cur) {
    List<BlockImpl> successorBlock = new ArrayList<>();

    if (cur.getType() == Block.BlockType.CONDITIONAL_BLOCK) {

      ConditionalBlock ccur = (ConditionalBlock) cur;

      successorBlock.add((BlockImpl) ccur.getThenSuccessor());
      successorBlock.add((BlockImpl) ccur.getElseSuccessor());

    } else {
      assert cur instanceof SingleSuccessorBlock;

      Block b = ((SingleSuccessorBlock) cur).getSuccessor();
      if (b != null) {
        successorBlock.add((BlockImpl) b);
      }
    }

    return successorBlock;
  }

  /**
   * If the input block is Regular, then it returns a list of nodes exist in the content. If it's an
   * Exception_Block, then it returns the corresponding node that causes exception. Otherwise, it
   * returns an emptyList.
   *
   * @param block
   * @return List of Nodes
   */
  private List<Node> getBlockNodes(Block block) {
    List<Node> blockNodes = new ArrayList<>();

    switch (block.getType()) {
      case REGULAR_BLOCK:
        blockNodes = ((RegularBlockImpl) block).getContents();
        break;

      case EXCEPTION_BLOCK:
        blockNodes.add(((ExceptionBlockImpl) block).getNode());
    }
    return blockNodes;
  }

  /**
   * It updates visited and worklist if the input state has not been visited yet
   *
   * @param state
   * @param visited
   * @param worklist
   */
  private void propagate(
      Pair<BlockImpl, Set<AssignmentNode>> state,
      Set<Pair<Long, Set<AssignmentNode>>> visited,
      Deque<Pair<BlockImpl, Set<AssignmentNode>>> worklist) {

    if (!visited.contains(Pair.of(state.first.getId(), state.second))) {
      visited.add(Pair.of(state.first.getId(), state.second));
      worklist.add(state);
    }
  }

  private String getAlwaysCallValue(Element element) {

    TypeMirror type = element.asType();
    TypeElement eType = TypesUtils.getTypeElement(type);
    AnnotationMirror alwaysCallAnnotation = getDeclAnnotation(eType, AlwaysCall.class);

    return (alwaysCallAnnotation != null)
        ? AnnotationUtils.getElementValue(alwaysCallAnnotation, "value", String.class, false)
        : null;
  }

  private void reportAlwaysCallErrors(
      AssignmentNode lastAssignmentNode, CFStore store, AnnotatedTypeMirror annotatedTypeMirror) {

    LocalVariableNode lhs = (LocalVariableNode) lastAssignmentNode.getTarget();
    CFValue lhsCFValue = store.getValue(lhs);
    String alwaysCallValue = getAlwaysCallValue(lhs.getElement());

    boolean report = true;

    if (lhsCFValue != null) { // When store contains the lhs

      for (AnnotationMirror annotationMirror : lhsCFValue.getAnnotations()) {
        if (getValueOfAnnotationWithStringArgument(annotationMirror).contains(alwaysCallValue)) {
          report = false;
        }
      }

    } else if (annotatedTypeMirror != null) {

      // Sometimes getStoreAfter doesn't contain correct set of local variable nodes! Then, it checks
      // the AlwaysCall condition by looking at AnnotatedTypeMirror
      AnnotationMirror annotationMirror = annotatedTypeMirror.getAnnotation(CalledMethods.class);

      if (annotationMirror != null
          && getValueOfAnnotationWithStringArgument(annotationMirror).contains(alwaysCallValue)) {
        report = false;
      }
    }

    if (report) {
      checker.report(
              lastAssignmentNode.getTree(),
              new DiagMessage(Diagnostic.Kind.ERROR, "missing.alwayscall", ""));
    }
  }

  private boolean hasAlwaysCall(TypeMirror type) {
    TypeElement eType = TypesUtils.getTypeElement(type);
    return ((eType != null) && (getDeclAnnotation(eType, AlwaysCall.class) != null));
  }

  /**
   * This tree annotator is needed to create types for fluent builders that have @This annotations.
   */
  private class ObjectConstructionTreeAnnotator extends TreeAnnotator {
    public ObjectConstructionTreeAnnotator(final AnnotatedTypeFactory atypeFactory) {
      super(atypeFactory);
    }

    @Override
    public Void visitMethodInvocation(
        final MethodInvocationTree tree, final AnnotatedTypeMirror type) {

      // Check to see if the ReturnsReceiver Checker has a @This annotation
      // on the return type of the method
      if (returnsThis(tree)) {

        // Fetch the current type of the receiver, or top if none exists
        ExpressionTree receiverTree = TreeUtils.getReceiverTree(tree.getMethodSelect());
        AnnotatedTypeMirror receiverType;
        AnnotationMirror receiverAnno;

        if (receiverTree != null && (receiverType = getAnnotatedType(receiverTree)) != null) {
          receiverAnno = receiverType.getAnnotationInHierarchy(TOP);
        } else {
          receiverAnno = TOP;
        }

        // Construct a new @CM annotation with just the method name
        String methodName = TreeUtils.methodName(tree).toString();
        methodName = adjustMethodNameUsingValueChecker(methodName, tree);
        AnnotationMirror cmAnno = createCalledMethods(methodName);

        // Replace the return type of the method with the GLB (= union) of the two types above
        AnnotationMirror newAnno = getQualifierHierarchy().greatestLowerBound(cmAnno, receiverAnno);
        type.replaceAnnotation(newAnno);
      }

      return super.visitMethodInvocation(tree, type);
    }

    /** handle a constructor call inside a toBuilder method generated by a framework */
    @Override
    public Void visitNewClass(NewClassTree tree, AnnotatedTypeMirror type) {

      for (FrameworkSupport frameworkSupport : frameworkSupports) {
        frameworkSupport.handleConstructor(tree, type);
      }

      return super.visitNewClass(tree, type);
    }
  }

  /**
   * adds @CalledMethod annotations for build() methods of AutoValue and Lombok Builders to ensure
   * required properties have been set
   */
  private class ObjectConstructionTypeAnnotator extends TypeAnnotator {

    public ObjectConstructionTypeAnnotator(AnnotatedTypeFactory atypeFactory) {
      super(atypeFactory);
    }

    @Override
    public Void visitExecutable(AnnotatedTypeMirror.AnnotatedExecutableType t, Void p) {
      ExecutableElement element = t.getElement();

      TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

      for (FrameworkSupport frameworkSupport : frameworkSupports) {
        frameworkSupport.handlePossibleToBuilder(t);
      }

      Element nextEnclosingElement = enclosingElement.getEnclosingElement();
      if (nextEnclosingElement.getKind().isClass()) {
        for (FrameworkSupport frameworkSupport : frameworkSupports) {
          frameworkSupport.handlePossibleBuilderBuildMethod(t);
        }
      }

      return super.visitExecutable(t, p);
    }
  }

  /**
   * The qualifier hierarchy is responsible for lub, glb, and subtyping between qualifiers without
   * declaratively defined subtyping relationships, like our @CalledMethods annotation.
   */
  private class ObjectConstructionQualifierHierarchy extends MultiGraphQualifierHierarchy {
    public ObjectConstructionQualifierHierarchy(
        final MultiGraphQualifierHierarchy.MultiGraphFactory factory) {
      super(factory);
    }

    @Override
    public AnnotationMirror getTopAnnotation(final AnnotationMirror start) {
      return TOP;
    }

    /**
     * GLB in this type system is set union of the arguments of the two annotations, unless one of
     * them is bottom, in which case the result is also bottom.
     */
    @Override
    public AnnotationMirror greatestLowerBound(
        final AnnotationMirror a1, final AnnotationMirror a2) {
      if (AnnotationUtils.areSame(a1, BOTTOM) || AnnotationUtils.areSame(a2, BOTTOM)) {
        return BOTTOM;
      }

      if (!AnnotationUtils.hasElementValue(a1, "value") || isCalledMethodsPredicate(a1)) {
        return a2;
      }

      if (!AnnotationUtils.hasElementValue(a2, "value") || isCalledMethodsPredicate(a2)) {
        return a1;
      }

      Set<String> a1Val = new LinkedHashSet<>(getValueOfAnnotationWithStringArgument(a1));
      Set<String> a2Val = new LinkedHashSet<>(getValueOfAnnotationWithStringArgument(a2));
      a1Val.addAll(a2Val);
      return createCalledMethods(a1Val.toArray(new String[0]));
    }

    private boolean isCalledMethodsPredicate(AnnotationMirror a1) {
      return AnnotationUtils.areSameByClass(a1, CalledMethodsPredicate.class);
    }

    /**
     * LUB in this type system is set intersection of the arguments of the two annotations, unless
     * one of them is bottom, in which case the result is the other annotation.
     */
    @Override
    public AnnotationMirror leastUpperBound(final AnnotationMirror a1, final AnnotationMirror a2) {
      if (AnnotationUtils.areSame(a1, BOTTOM)) {
        return a2;
      } else if (AnnotationUtils.areSame(a2, BOTTOM)) {
        return a1;
      }

      if (!AnnotationUtils.hasElementValue(a1, "value")) {
        return a1;
      }

      if (!AnnotationUtils.hasElementValue(a2, "value")) {
        return a2;
      }

      if (isCalledMethodsPredicate(a1) || isCalledMethodsPredicate(a2)) {
        return TOP;
      }

      Set<String> a1Val = new LinkedHashSet<>(getValueOfAnnotationWithStringArgument(a1));
      Set<String> a2Val = new LinkedHashSet<>(getValueOfAnnotationWithStringArgument(a2));
      a1Val.retainAll(a2Val);
      return createCalledMethods(a1Val.toArray(new String[0]));
    }

    /** isSubtype in this type system is subset */
    @Override
    public boolean isSubtype(final AnnotationMirror subAnno, final AnnotationMirror superAnno) {

      if (AnnotationUtils.areSame(subAnno, BOTTOM)) {
        return true;
      } else if (AnnotationUtils.areSame(superAnno, BOTTOM)) {
        return false;
      }

      if (AnnotationUtils.areSame(superAnno, TOP)) {
        return true;
      }
      // Do not symmetrically check top here because some @CalledMethodsPredicate
      // annotations involving ! are equivalent to top.

      if (AnnotationUtils.areSameByClass(subAnno, CalledMethodsPredicate.class)) {
        String subPredicate =
            AnnotationUtils.getElementValue(subAnno, "value", String.class, false);
        if (AnnotationUtils.areSameByClass(superAnno, CalledMethodsPredicate.class)) {
          String superPredicate =
              AnnotationUtils.getElementValue(superAnno, "value", String.class, false);
          // shortcut if they're equal (most common case) to avoid calling the SMT solver
          if (superPredicate.equals(subPredicate)) {
            return true;
          } else {
            return CalledMethodsPredicateEvaluator.implies(subPredicate, superPredicate);
          }
        } else if (AnnotationUtils.areSameByClass(superAnno, CalledMethods.class)) {
          // If the supertype is a called methods type, treat it as a conjunction of its
          // arguments and use the predicate evaluator.
          List<String> superVals = getValueOfAnnotationWithStringArgument(superAnno);
          String superPredicate = String.join(" && ", superVals);
          return CalledMethodsPredicateEvaluator.implies(subPredicate, superPredicate);
        }
      }

      if (AnnotationUtils.areSameByClass(subAnno, CalledMethods.class)
          || AnnotationUtils.areSame(subAnno, TOP)) {
        // Treat top as @CalledMethods({}) so that predicates with ! are evaluated correctly.
        List<String> subVals =
            AnnotationUtils.areSame(subAnno, TOP)
                ? Collections.emptyList()
                : getValueOfAnnotationWithStringArgument(subAnno);

        if (AnnotationUtils.areSameByClass(superAnno, CalledMethodsPredicate.class)) {
          // superAnno is a CMP annotation, so we need to evaluate the predicate
          String predicate =
              AnnotationUtils.getElementValue(superAnno, "value", String.class, false);
          return CalledMethodsPredicateEvaluator.evaluate(predicate, subVals);
        } else if (AnnotationUtils.areSameByClass(superAnno, CalledMethods.class)) {
          // superAnno is a CM annotation, so compare the sets
          return subVals.containsAll(getValueOfAnnotationWithStringArgument(superAnno));
        }
      }

      // should never happen: all possible subtypes already handled
      throw new BugInCF(
          "ObjectConstructionAnnotatedTypeFactory.isSubType: unreachable case for subanno="
              + subAnno);
    }
  }

  /**
   * Gets the value field of an annotation with a list of strings in its value element (field).
   *
   * @param anno the annotation whose value element to read
   * @return the strings in the annotation's value element, or null if the annotation has no value
   *     field.
   */
  public static List<String> getValueOfAnnotationWithStringArgument(final AnnotationMirror anno) {
    if (!AnnotationUtils.hasElementValue(anno, "value")) {
      return Collections.emptyList();
    }
    return AnnotationUtils.getElementValueArray(anno, "value", String.class, true);
  }

  private boolean hasAnnotation(Element element, Class<? extends Annotation> annotClass) {
    return element.getAnnotation(annotClass) != null;
  }

  @Override
  protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
    return getBundledTypeQualifiers(
        CalledMethods.class,
        CalledMethodsBottom.class,
        CalledMethodsPredicate.class,
        CalledMethodsTop.class);
  }

  private boolean hasAnnotation(Element element, String annotName) {
    return element.getAnnotationMirrors().stream()
        .anyMatch(anm -> AnnotationUtils.areSameByName(anm, annotName));
  }

  /**
   * Returns the annotation type mirror for the type of {@code expressionTree} with default
   * annotations applied. As types relevant to object construction checking are rarely used inside
   * generics, this is typically the best choice for type inference.
   */
  @Override
  public @Nullable AnnotatedTypeMirror getDummyAssignedTo(ExpressionTree expressionTree) {
    TypeMirror type = TreeUtils.typeOf(expressionTree);
    if (type.getKind() != TypeKind.VOID) {
      AnnotatedTypeMirror atm = type(expressionTree);
      addDefaultAnnotations(atm);
      return atm;
    }
    return null;
  }

  public CFAnalysis getAnalysis() {
    final CFAnalysis analysis = this.analysis;
    return analysis;
  }

  Collection<FrameworkSupport> getFrameworkSupports() {
    return frameworkSupports;
  }
}
