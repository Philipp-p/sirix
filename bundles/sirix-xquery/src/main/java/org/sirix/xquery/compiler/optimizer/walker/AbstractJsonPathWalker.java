package org.sirix.xquery.compiler.optimizer.walker;

import org.brackit.xquery.atomic.Int32;
import org.brackit.xquery.atomic.QNm;
import org.brackit.xquery.compiler.AST;
import org.brackit.xquery.compiler.XQ;
import org.brackit.xquery.compiler.optimizer.walker.Walker;
import org.brackit.xquery.function.json.JSONFun;
import org.brackit.xquery.module.StaticContext;
import org.brackit.xquery.util.path.Path;
import org.sirix.access.trx.node.IndexController;
import org.sirix.api.json.JsonNodeReadOnlyTrx;
import org.sirix.api.json.JsonNodeTrx;
import org.sirix.index.IndexDef;
import org.sirix.node.NodeKind;
import org.sirix.xquery.json.JsonDBStore;

import java.util.*;

abstract class AbstractJsonPathWalker extends Walker {

  private static final int MIN_NODE_NUMBER = 0;

  private final JsonDBStore jsonDBStore;

  public AbstractJsonPathWalker(StaticContext sctx, JsonDBStore jsonDBStore) {
    super(sctx);
    this.jsonDBStore = jsonDBStore;
  }

  protected AST replaceAstIfIndexApplicable(AST astNode, AST predicateNode) {
    boolean foundDerefAncestor = findDerefAncestor(astNode);

    if (!foundDerefAncestor && (astNode.getChild(0).getType() == XQ.DerefExpr
        || astNode.getChild(0).getType() == XQ.ArrayAccess || astNode.getChild(0).getType() == XQ.FunctionCall)) {
      final var pathSegmentNames = new ArrayDeque<String>();
      final var arrayIndexes = new HashMap<String, Deque<Integer>>();

      if (predicateNode != null) {
        final var pathSegmentName = predicateNode.getChild(astNode.getChildCount() - 1).getStringValue();
        pathSegmentNames.add(pathSegmentName);
        final var predicateLeafNode = getPredicatePathStep(predicateNode, pathSegmentNames, arrayIndexes);
        if (predicateLeafNode.isEmpty()) {
          return null;
        }
      }

      final var predicateSegmentNames = new ArrayDeque<>(pathSegmentNames);

      final var pathSegmentName = astNode.getChild(astNode.getChildCount() - 1).getStringValue();
      pathSegmentNames.add(pathSegmentName);
      final var newNode = getPathStep(astNode, pathSegmentNames, arrayIndexes);

      if (newNode.isEmpty() || pathSegmentNames.size() <= 1) {
        return astNode;
      }

      final var newChildNode = newNode.get();

      if (isDocumentNodeFunction(newChildNode)) {
        final var databaseName = newChildNode.getChild(0).getStringValue();
        final var resourceName = newChildNode.getChild(1).getStringValue();

        final int revision;
        if (newChildNode.getChildCount() > 2) {
          revision = (int) newChildNode.getChild(2).getValue();
        } else {
          revision = -1;
        }

        try (final var jsonCollection = jsonDBStore.lookup(databaseName);
             final var resMgr = jsonCollection.getDatabase().openResourceManager(resourceName);
             final var rtx = revision == -1 ? resMgr.beginNodeReadOnlyTrx() : resMgr.beginNodeReadOnlyTrx(revision);
             final var pathSummary = revision == -1 ? resMgr.openPathSummary() : resMgr.openPathSummary(revision)) {
          if (rtx.getDescendantCount() < MIN_NODE_NUMBER) {
            return astNode;
          }

          var pathNodeKeysToRemove = new ArrayList<Integer>();
          var pathNodeKeys = new ArrayList<Integer>();

          final var pathSegmentNameToCheck = pathSegmentNames.removeFirst();

          var pathNodeKeyBitmap = pathSummary.match(new QNm(pathSegmentNameToCheck), 0, NodeKind.OBJECT_KEY);

          if (pathNodeKeyBitmap.isEmpty()) {
            final var parentASTNode = astNode.getParent();
            parentASTNode.replaceChild(astNode.getChildIndex(), new AST(XQ.SequenceExpr));
          }

          for (int i = pathNodeKeyBitmap.nextSetBit(0); i >= 0; i = pathNodeKeyBitmap.nextSetBit(i + 1)) {
            // operate on index i here
            pathNodeKeys.add(i);

            if (i == Integer.MAX_VALUE) {
              break; // or (i+1) would overflow
            }
          }

          for (final var pathNodeKey : pathNodeKeys) {
            final var currentPathSegmentNames = new ArrayDeque<>(pathSegmentNames);
            pathSummary.moveTo(pathNodeKey);
            var candidatePath = pathSummary.getPath();
            String pathSegment = currentPathSegmentNames.removeFirst();
            assert candidatePath != null;
            final var pathSteps = candidatePath.steps();
            boolean found = false;

            for (int i = pathSteps.size() - 1; i >= 0; i--) {
              final var step = pathSteps.get(i);

              if (found && currentPathSegmentNames.isEmpty() && !Path.Axis.CHILD_ARRAY.equals(step.getAxis())) {
                break;
              } else if (step.getAxis() == Path.Axis.CHILD && step.getValue().equals(new QNm(pathSegment))) {
                found = true;

                if (!currentPathSegmentNames.isEmpty()) {
                  pathSegment = currentPathSegmentNames.removeFirst();
                }
              }
            }

            if (!currentPathSegmentNames.isEmpty() || !found) {
              pathNodeKeysToRemove.add(pathNodeKey);
            }
          }

          pathNodeKeys.removeIf(pathNodeKeysToRemove::contains);

          if (pathNodeKeys.isEmpty()) {
            final var parentASTNode = astNode.getParent();
            final var emptySequence = new AST(XQ.SequenceExpr);
            parentASTNode.replaceChild(astNode.getChildIndex(), emptySequence);
            return emptySequence;
          }

          boolean notFound = false;
          final var foundIndexDefsToPaths = new HashMap<IndexDef, List<Path<QNm>>>();
          final var foundIndexDefsToPredicateLevels = new HashMap<IndexDef, Integer>();

          for (final int pathNodeKey : pathNodeKeys) {
            final var foundPathNode = pathSummary.getPathNodeForPathNodeKey(pathNodeKey);
            assert foundPathNode != null;
            final var pathToFoundNode = foundPathNode.getPath(pathSummary);

            final var indexController = revision == -1
                ? resMgr.getRtxIndexController(resMgr.getMostRecentRevisionNumber())
                : resMgr.getRtxIndexController(revision);

            final var indexDef = findIndex(pathToFoundNode, indexController);

            if (indexDef.isEmpty()) {
              notFound = true;
              break;
            }

            if (predicateNode != null) {
              int predicateLevel = getPredicateLevel(pathToFoundNode, predicateSegmentNames);
              foundIndexDefsToPredicateLevels.put(indexDef.get(), predicateLevel);
            }

            foundIndexDefsToPaths.computeIfAbsent(indexDef.get(), (unused) -> new ArrayList<>()).add(pathToFoundNode);
          }

          if (!notFound) {
            return replaceFoundAST(astNode,
                                   databaseName,
                                   resourceName,
                                   revision,
                                   foundIndexDefsToPaths,
                                   foundIndexDefsToPredicateLevels,
                                   arrayIndexes);
          }
        }
      }
    }
    return null;
  }

  abstract int getPredicateLevel(Path<QNm> pathToFoundNode, Deque<String> predicateSegmentNames);

  abstract AST replaceFoundAST(AST astNode, String databaseName, String resourceName, int revision,
      Map<IndexDef, List<Path<QNm>>> foundIndexDefs, Map<IndexDef, Integer> predicateLevel,
      Map<String, Deque<Integer>> arrayIndexes);

  abstract Optional<IndexDef> findIndex(Path<QNm> pathToFoundNode,
      IndexController<JsonNodeReadOnlyTrx, JsonNodeTrx> indexController);

  boolean isDocumentNodeFunction(AST newChildNode) {
    return new QNm(JSONFun.JSON_NSURI, JSONFun.JSON_PREFIX, "doc").equals(newChildNode.getValue())
        || new QNm(JSONFun.JSON_NSURI, JSONFun.JSON_PREFIX, "open").equals(newChildNode.getValue());
  }

  boolean findDerefAncestor(AST astNode) {
    boolean foundDerefAncestor = false;
    AST ancestor = astNode.getParent();
    while (ancestor != null && !foundDerefAncestor) {
      foundDerefAncestor = ancestor.getType() == XQ.DerefExpr;
      ancestor = ancestor.getParent();
    }
    return foundDerefAncestor;
  }

  abstract Optional<AST> getPredicatePathStep(AST node, Deque<String> pathNames,
      Map<String, Deque<Integer>> arrayIndexes);

  Optional<AST> getPathStep(AST node, Deque<String> pathNames, Map<String, Deque<Integer>> arrayIndexes) {
    for (int i = 0, length = node.getChildCount(); i < length; i++) {
      final var step = node.getChild(i);

      if (step.getType() == XQ.ArrayAccess) {
        if (step.getChildCount() == 2) {
          final var arrayAstNode = processArrayAccess(null, arrayIndexes, step);

          final var derefAstNode = arrayAstNode.getChild(0);
          final var indexAstNode = arrayAstNode.getChild(1);

          final var pathSegmentName = derefAstNode.getChild(step.getChildCount() - 1).getStringValue();
          pathNames.add(pathSegmentName);

          arrayIndexes.computeIfAbsent(pathSegmentName, (unused) -> new ArrayDeque<>())
                      .add(((Int32) indexAstNode.getValue()).intValue());

          return getPathStep(derefAstNode, pathNames, arrayIndexes);
        }
      }

      if (step.getType() == XQ.DerefExpr) {
        final var pathSegmentName = step.getChild(step.getChildCount() - 1).getStringValue();
        pathNames.add(pathSegmentName);
        return getPathStep(step, pathNames, arrayIndexes);
      }

      if (step.getType() == XQ.FunctionCall) {
        return Optional.of(step);
      }
    }

    return Optional.empty();
  }

  protected AST processArrayAccess(String pathNameForIndexes, Map<String, Deque<Integer>> arrayIndexes, AST astNode) {
    if (astNode.getType() == XQ.ArrayAccess) {
      var firstChildAstNode = astNode.getChild(0);
      final var indexAstNode = astNode.getChild(1);

      if (firstChildAstNode.getType() == XQ.ArrayAccess) {
        if (pathNameForIndexes == null) {
          var clonedAstNode = firstChildAstNode.copyTree();
          while (clonedAstNode.getChild(0).getType() == XQ.ArrayAccess) {
            clonedAstNode = clonedAstNode.getChild(0);
          }
          final var derefAstNode = clonedAstNode.getChild(0);
          pathNameForIndexes = derefAstNode.getChild(1).getStringValue();
        }

        arrayIndexes.computeIfAbsent(pathNameForIndexes, (unused) -> new ArrayDeque<>())
                    .add(((Int32) indexAstNode.getValue()).intValue());
        return processArrayAccess(pathNameForIndexes, arrayIndexes, firstChildAstNode);
      }
    }
    return astNode;
  }
}
