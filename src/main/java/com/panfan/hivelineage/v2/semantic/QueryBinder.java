package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.DatasetVersionId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.KnownOutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.relation.UnknownOutputSegment;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.BoundRelationReference;
import com.panfan.hivelineage.v2.ir.statement.BoundQueryBlockReference;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.syntax.BinaryOperatorSyntax;
import com.panfan.hivelineage.v2.syntax.BinarySyntax;
import com.panfan.hivelineage.v2.syntax.CommaFromSyntax;
import com.panfan.hivelineage.v2.syntax.CteSyntax;
import com.panfan.hivelineage.v2.syntax.DerivedFromSyntax;
import com.panfan.hivelineage.v2.syntax.FromSyntax;
import com.panfan.hivelineage.v2.syntax.GenerateSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.JoinFromSyntax;
import com.panfan.hivelineage.v2.syntax.LateralFromSyntax;
import com.panfan.hivelineage.v2.syntax.NameRefSyntax;
import com.panfan.hivelineage.v2.syntax.NamedFromSyntax;
import com.panfan.hivelineage.v2.syntax.NamedWindowSyntax;
import com.panfan.hivelineage.v2.syntax.QuantifierSyntax;
import com.panfan.hivelineage.v2.syntax.QueryClausesSyntax;
import com.panfan.hivelineage.v2.syntax.QueryTailSyntax;
import com.panfan.hivelineage.v2.syntax.SelectBodySyntax;
import com.panfan.hivelineage.v2.syntax.SelectItemSyntax;
import com.panfan.hivelineage.v2.syntax.SelectQuerySyntax;
import com.panfan.hivelineage.v2.syntax.SetQuerySyntax;
import com.panfan.hivelineage.v2.syntax.StarSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.SyntaxQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;

final class QueryBinder {
    private final BindingWork work;
    private final ExpressionBinder expressions;
    private final ReferenceBinder references;
    private final DefinitionExpander definitions;

    QueryBinder(BindingWork work) {
        this.work = work;
        this.expressions = new ExpressionBinder(work);
        this.references = new ReferenceBinder(work);
        this.definitions = new DefinitionExpander(work, (syntax, scope, outputs, role, prefix, path)
                -> query(syntax, scope, outputs, role, prefix, null, path));
    }

    BoundRelation query(SyntaxQuery syntax, ReferenceScope outer, boolean outputs, QueryResult.Role role, String prefix) {
        return query(syntax, outer, outputs, role, prefix, null);
    }

    BoundRelation query(SyntaxQuery syntax, ReferenceScope outer, boolean outputs, QueryResult.Role role,
                        String prefix, FromSyntax fallbackFrom) {
        return query(syntax, outer, outputs, role, prefix, fallbackFrom, null);
    }

    BoundRelation query(SyntaxQuery syntax, ReferenceScope outer, boolean outputs, QueryResult.Role role,
                        String prefix, FromSyntax fallbackFrom, String displayPath) {
        com.panfan.hivelineage.v2.ir.identity.QueryBlockId block = prefix.isEmpty() ? syntax.getQueryBlockId()
                : new com.panfan.hivelineage.v2.ir.identity.QueryBlockId(prefix + syntax.getQueryBlockId().getValue());
        ReferenceScope scope = new ReferenceScope(work, outer, new ScopeId(prefix + syntax.getNodeId().getValue() + "/scope"),
                block, prefix, syntax.getCtes());
        if (displayPath != null) {
            scope.displayPath = displayPath;
        } else if (role == QueryResult.Role.MAIN) {
            scope.displayPath = "root";
        }
        work.queryBlocks.put(scope.block, new BoundQueryBlockReference(scope.block, scope.displayPath, scope.analyzeJoins));
        if (work.requested(BindingRequirement.CONDITION_FIELDS) || outputs) {
            for (CteSyntax cte : syntax.getCtes()) {
                cte(cte, scope, outputs || work.requested(BindingRequirement.CONDITION_FIELDS));
            }
        }
        BoundRelation result;
        if (syntax instanceof SetQuerySyntax) {
            result = set((SetQuerySyntax) syntax, scope, outputs);
        } else {
            result = select((SelectQuerySyntax) syntax, scope, outputs, fallbackFrom);
        }
        work.queryResults.add(new QueryResult(result.getId(), scope.block, result.getOutputSchema(), role));
        return result;
    }

    private BoundRelation select(SelectQuerySyntax syntax, ReferenceScope scope, boolean outputs, FromSyntax fallbackFrom) {
        BoundSources sources = bindSources(syntax, scope, outputs, fallbackFrom);
        if (!outputs) {
            return bindObjectProjection(syntax, scope, sources);
        }
        BoundProjection projection = bindProjection(syntax, scope);
        scope.setOutputAliases(projection.layout.visibleSchema());
        PostProjectionClauses post = bindPostProjection(syntax, scope, projection);
        // All source-field discovery has finished before constructing operators that copy its schema.
        BoundRelation input = completeSources(sources);
        return assembleSelect(syntax, scope, input, projection, post);
    }

    private BoundSources bindSources(SelectQuerySyntax syntax, ReferenceScope scope, boolean outputs, FromSyntax fallbackFrom) {
        SelectBodySyntax body = syntax.getBody();
        QueryClausesSyntax clauses = body.getClauses();
        for (NamedWindowSyntax window : clauses.getWindows()) {
            scope.windows.put(SemanticNames.normalize(window.getName().getValue()), window);
        }
        FromSyntax from = body.getFrom() == null ? fallbackFrom : body.getFrom();
        BoundRelation input;
        if (from == null) {
            input = new Relations.Values(work.id(syntax, scope, "one-row"), OutputSchema.known(Collections.<OutputSlot>emptyList()),
                    scope.block, order(syntax), syntax.getSourceRef(), Collections.singletonList(Collections.<BoundExpression>emptyList()));
            work.put(input);
        } else {
            input = from(from, scope, outputs);
        }
        if (clauses.getWhere() != null) {
            scope.clauseLocation = ClauseLocation.WHERE;
            BoundExpression predicate = null;
            if (outputs || work.requested(BindingRequirement.CONDITION_FIELDS)) {
                predicate = expressions.bind(clauses.getWhere(), scope);
                if (from instanceof CommaFromSyntax) {
                    input = commaConditions(input, clauses.getWhere(), scope);
                }
            } else {
                predicate = objectPredicate(clauses.getWhere(), scope, BindingRequirement.CONDITION_FIELDS);
            }
            if (predicate != null) {
                input = new Relations.Filter(work.id(syntax, scope, "where"), input.getOutputSchema(), scope.block,
                        order(syntax), clauses.getWhere().getSourceRef(), input.getId(), predicate, BindingWork.mappings(input.getOutputSchema()));
                work.put(input);
            }
            scope.clauseLocation = ClauseLocation.SELECT;
        }
        return new BoundSources(input);
    }

    private BoundRelation completeSources(BoundSources sources) {
        return refresh(sources.input.getId(), new LinkedHashSet<NodeId>());
    }

    private BoundRelation bindObjectProjection(SelectQuerySyntax syntax, ReferenceScope scope, BoundSources sources) {
        SelectBodySyntax body = syntax.getBody();
        QueryClausesSyntax clauses = body.getClauses();
        BoundRelation input = sources.input;
        input = refresh(input.getId(), new LinkedHashSet<NodeId>());
        List<NodeId> projectionQueries = new ArrayList<>();
        for (SelectItemSyntax item : body.getSelectItems()) {
            collectSubqueryRoots(item.getExpression(), scope, projectionQueries);
        }
        new SyntaxSubqueryCollector(work, this).collectClauses(clauses, scope, projectionQueries, true);
        if (!projectionQueries.isEmpty()) {
            BoundExpression dependency = new Expressions.NotRequestedExpression(work.id(syntax, scope, "select-not-requested"),
                    TypeRef.unknown(), syntax.getSourceRef(), BindingRequirement.OUTPUT_SCHEMA, projectionQueries);
            input = new Relations.Filter(work.id(syntax, scope, "select-dependencies"), input.getOutputSchema(), scope.block,
                    order(syntax), syntax.getSourceRef(), input.getId(), dependency, BindingWork.mappings(input.getOutputSchema()));
            work.put(input);
        }
        if (clauses.getHaving() != null) {
            scope.clauseLocation = ClauseLocation.HAVING;
            BoundExpression having = work.requested(BindingRequirement.CONDITION_FIELDS)
                    ? expressions.bind(clauses.getHaving(), scope)
                    : objectPredicate(clauses.getHaving(), scope, BindingRequirement.CONDITION_FIELDS);
            input = refresh(input.getId(), new LinkedHashSet<NodeId>());
            input = new Relations.Filter(work.id(syntax, scope, "having"), input.getOutputSchema(), scope.block, order(syntax),
                    clauses.getHaving().getSourceRef(), input.getId(), having, BindingWork.mappings(input.getOutputSchema()));
            work.put(input);
            scope.clauseLocation = ClauseLocation.SELECT;
        }
        BoundRelation projection = new Relations.Project(work.id(syntax, scope, "project"), OutputSchema.notRequested(),
                scope.block, order(syntax), syntax.getSourceRef(), input.getId(), Collections.<OutputAssignment>emptyList());
        work.put(projection);
        return projection;
    }

    private BoundProjection bindProjection(SelectQuerySyntax syntax, ReferenceScope scope) {
        SelectBodySyntax body = syntax.getBody();
        QueryClausesSyntax clauses = body.getClauses();
        ProjectionLayout.Builder layout = new ProjectionLayout.Builder();
        int position = 0;
        for (SelectItemSyntax item : body.getSelectItems()) {
            if (item.getExpression() instanceof StarSyntax) {
                StarSyntax star = (StarSyntax) item.getExpression();
                List<SourceBinding> selected = starSources(star, scope);
                if (selected.isEmpty()) {
                    layout.addUnknown(new UnknownOutputSegment(work.id(star, scope, "star"), "UNRESOLVED_STAR"));
                    work.warn("UNRESOLVED_STAR", "Star owner is unavailable", star.getSourceRef());
                }
                for (SourceBinding source : selected) {
                    references.loadSchema(source, scope);
                    if (source.schema().getState() != SchemaState.KNOWN) {
                        layout.addUnknown(new UnknownOutputSegment(work.id(star, scope, "star-" + source.instanceId.getValue()), "SCHEMA_UNKNOWN"));
                        continue;
                    }
                    List<ProjectionLayout.Column> expanded = new ArrayList<>();
                    for (OutputSlot inputSlot : BindingWork.slots(source.schema())) {
                        OutputSlot output = new OutputSlot(new SlotId(work.id(star, scope, "output-" + position++).getValue()),
                                inputSlot.getDisplayName(), inputSlot.getType(), null);
                        expanded.add(new ProjectionLayout.Column(output, new Expressions.SlotRef(
                                new NodeId(output.getId().getValue() + "/reference"), inputSlot.getType(), star.getSourceRef(),
                                inputSlot.getId(), Collections.<String>emptyList())));
                    }
                    layout.addVisibleGroup(expanded);
                }
            } else {
                BoundExpression expression = expressions.bind(item.getExpression(), scope);
                String name = outputName(item, position);
                OutputSlot output = new OutputSlot(new SlotId(work.id(item.getExpression(), scope, "output-" + position++).getValue()),
                        name, work.analyzeType(expression), null);
                layout.addVisible(output, expression);
            }
        }
        List<BoundExpression> groupings = new ArrayList<>();
        if (clauses.getGrouping() != null) {
            scope.clauseLocation = ClauseLocation.GROUP_BY;
            for (List<SyntaxExpression> group : clauses.getGrouping().getSets()) {
                for (SyntaxExpression expression : group) {
                    groupings.add(expressions.bind(expression, scope));
                }
            }
            scope.clauseLocation = ClauseLocation.SELECT;
        }
        return new BoundProjection(layout.build(), groupings);
    }

    private PostProjectionClauses bindPostProjection(SelectQuerySyntax syntax, ReferenceScope scope, BoundProjection projection) {
        QueryClausesSyntax clauses = syntax.getBody().getClauses();
        ProjectionLayout.Builder layout = projection.layout.toBuilder();
        List<BoundExpression> groupings = projection.groupings;
        boolean aggregate = !groupings.isEmpty();
        for (OutputAssignment assignment : projection.layout.assignments()) {
            aggregate |= aggregate(assignment.getExpression());
        }
        Map<SlotId, BoundExpression> outputExpressions = layout.visibleExpressions();
        // Distribution keys may contain queries even though their execution operator is not modeled.
        // Bind them as hidden computations, preserving object dependencies without inserting value mappings.
        distributionOutputs(syntax, clauses.getTail(), scope, outputExpressions, layout);
        BoundExpression having = null;
        if (clauses.getHaving() != null) {
            scope.clauseLocation = ClauseLocation.HAVING;
            BoundExpression predicate = OutputAliasExpander.expand(expressions.bind(clauses.getHaving(), scope), outputExpressions);
            aggregate |= aggregate(predicate);
            SlotId slot = hiddenOutput(syntax, scope, "having", predicate, layout);
            having = hiddenReference(syntax, scope, "having", predicate, slot);
            scope.clauseLocation = ClauseLocation.SELECT;
        }
        List<Relations.OrderKey> orderKeys = new ArrayList<>();
        List<Relations.OrderKey> boundOrderKeys = clauseOrders(clauses.getTail(), scope);
        int orderIndex = 0;
        for (Relations.OrderKey key : boundOrderKeys) {
            BoundExpression expression = OutputAliasExpander.expand(key.getExpression(), outputExpressions);
            String role = "order-" + orderIndex++;
            SlotId slot = hiddenOutput(syntax, scope, role, expression, layout);
            orderKeys.add(new Relations.OrderKey(hiddenReference(syntax, scope, role, expression, slot),
                    key.getDirection(), key.getNullOrder()));
        }
        return new PostProjectionClauses(layout.build(), having, orderKeys, aggregate);
    }

    private BoundRelation assembleSelect(SelectQuerySyntax syntax, ReferenceScope scope, BoundRelation input,
                                         BoundProjection bound, PostProjectionClauses post) {
        SelectBodySyntax body = syntax.getBody();
        QueryClausesSyntax clauses = body.getClauses();
        OutputSchema schema = post.layout.visibleSchema();
        List<OutputAssignment> assignments = post.layout.assignments();
        List<BoundExpression> groupings = bound.groupings;
        BoundExpression having = post.having;
        List<Relations.OrderKey> orderKeys = post.orderKeys;
        boolean aggregate = post.aggregate;
        // HAVING and ORDER BY can refer to fields that SELECT does not expose.
        // Evaluate them with the projection/aggregate, then use their output slots.
        OutputSchema operatorSchema = post.layout.operatorSchema();
        if (!scope.windowsLayout.computations().isEmpty()) {
            OutputSchema windowSchema = BindingWork.combined(input.getOutputSchema(), OutputSchema.known(scope.windowsLayout.slots()), true);
            BoundRelation window = new Relations.Window(work.id(syntax, scope, "window"), windowSchema, scope.block,
                    order(syntax), syntax.getSourceRef(), input.getId(), BindingWork.mappings(input.getOutputSchema()), scope.windowsLayout.computations());
            work.put(window);
            input = window;
        }
        BoundRelation projection = aggregate
                ? new Relations.Aggregate(work.id(syntax, scope, "aggregate"), operatorSchema, scope.block, order(syntax),
                syntax.getSourceRef(), input.getId(), groupings, assignments)
                : new Relations.Project(work.id(syntax, scope, "project"), operatorSchema, scope.block, order(syntax),
                syntax.getSourceRef(), input.getId(), assignments);
        work.put(projection);
        if (having != null) {
            projection = new Relations.Filter(work.id(syntax, scope, "having"), operatorSchema, scope.block, order(syntax),
                    clauses.getHaving().getSourceRef(), projection.getId(), having, BindingWork.mappings(operatorSchema));
            work.put(projection);
        }
        if (body.getQuantifier() == QuantifierSyntax.DISTINCT) {
            List<SlotId> keys = new ArrayList<>();
            for (OutputSlot slot : BindingWork.slots(schema)) {
                keys.add(slot.getId());
            }
            projection = new Relations.Distinct(work.id(syntax, scope, "distinct"), operatorSchema, scope.block, order(syntax),
                    syntax.getSourceRef(), projection.getId(), keys, BindingWork.mappings(operatorSchema));
            work.put(projection);
        }
        projection = tail(projection, clauses.getTail(), syntax, scope, orderKeys);
        if (post.layout.hasHidden()) {
            ProjectionLayout visible = post.layout.visibleProjection(syntax.getSourceRef());
            OutputSchema visibleSchema = visible.visibleSchema();
            scope.setOutputAliases(visibleSchema);
            projection = new Relations.Project(work.id(syntax, scope, "visible-project"), visibleSchema, scope.block,
                    order(syntax), syntax.getSourceRef(), projection.getId(), visible.assignments());
            work.put(projection);
        }
        return projection;
    }

    private static final class BoundSources {
        final BoundRelation input;

        BoundSources(BoundRelation input) {
            this.input = input;
        }
    }

    private static final class BoundProjection {
        final ProjectionLayout layout;
        final List<BoundExpression> groupings;

        BoundProjection(ProjectionLayout layout, List<BoundExpression> groupings) {
            this.layout = layout;
            this.groupings = Collections.unmodifiableList(new ArrayList<>(groupings));
        }
    }

    private static final class PostProjectionClauses {
        final ProjectionLayout layout;
        final BoundExpression having;
        final List<Relations.OrderKey> orderKeys;
        final boolean aggregate;

        PostProjectionClauses(ProjectionLayout layout, BoundExpression having, List<Relations.OrderKey> orderKeys, boolean aggregate) {
            this.layout = layout;
            this.having = having;
            this.orderKeys = Collections.unmodifiableList(new ArrayList<>(orderKeys));
            this.aggregate = aggregate;
        }
    }

    private BoundRelation set(SetQuerySyntax syntax, ReferenceScope scope, boolean outputs) {
        List<BoundRelation> branches = new ArrayList<>();
        List<NodeId> ids = new ArrayList<>();
        for (SyntaxQuery branch : syntax.getBody().getBranches()) {
            BoundRelation bound = query(branch, scope, outputs, QueryResult.Role.SUBQUERY, scope.prefix);
            branches.add(bound);
            ids.add(bound.getId());
        }
        List<OutputSlot> slots = new ArrayList<>();
        List<Relations.SetOutputMapping> mappings = new ArrayList<>();
        boolean known = outputs;
        BoundRelation shape = null;
        for (BoundRelation branch : branches) {
            if (branch.getOutputSchema().getState() == SchemaState.KNOWN) {
                shape = branch;
                break;
            }
        }
        int width = shape == null ? 0 : BindingWork.slots(shape.getOutputSchema()).size();
        for (BoundRelation branch : branches) {
            known &= branch.getOutputSchema().getState() == SchemaState.KNOWN;
            if (branch.getOutputSchema().getState() == SchemaState.KNOWN
                    && shape != null
                    && BindingWork.slots(branch.getOutputSchema()).size() != width) {
                work.error("COLUMN_COUNT_MISMATCH", "Set operation branches have different widths", syntax.getSourceRef());
            }
        }
        if (outputs) {
            for (int index = 0; index < width; index++) {
                List<Relations.BranchSlot> inputs = new ArrayList<>();
                OutputSlot first = BindingWork.slots(shape.getOutputSchema()).get(index);
                boolean namedByFirstBranch = branches.get(0).getOutputSchema().getState() == SchemaState.KNOWN;
                OutputSlot output = new OutputSlot(new SlotId(work.id(syntax, scope, "set-output-" + index).getValue()),
                        namedByFirstBranch ? first.getDisplayName() : "_col" + index, first.getType(), null);
                slots.add(output);
                for (BoundRelation branch : branches) {
                    List<OutputSlot> branchSlots = BindingWork.slots(branch.getOutputSchema());
                    inputs.add(branch.getOutputSchema().getState() == SchemaState.KNOWN && index < branchSlots.size()
                            ? Relations.BranchSlot.known(branchSlots.get(index).getId())
                            : Relations.BranchSlot.unknown("SET_BRANCH_WIDTH_UNKNOWN"));
                }
                mappings.add(new Relations.SetOutputMapping(output.getId(), inputs));
            }
        }
        OutputSchema schema = !outputs ? OutputSchema.notRequested() : known ? OutputSchema.known(slots)
                                                                       : partialSchema(slots, work.id(syntax, scope, "set-width"), "SET_BRANCH_WIDTH_UNKNOWN");
        BoundRelation result = new Relations.SetOp(work.id(syntax, scope, "set"), schema, scope.block, order(syntax),
                syntax.getSourceRef(), ids, Relations.SetOperator.valueOf(syntax.getBody().getOperator().name()),
                syntax.getBody().getQuantifier() == QuantifierSyntax.ALL ? Relations.DuplicatePolicy.ALL : Relations.DuplicatePolicy.DISTINCT, mappings);
        work.put(result);
        scope.setOutputAliases(schema);
        return outputs ? tail(result, syntax.getBody().getTail(), syntax, scope) : result;
    }

    BoundRelation from(FromSyntax syntax, ReferenceScope scope, boolean outputs) {
        BoundFrom from = bindFrom(syntax, scope, outputs);
        scope.symbols.registerAll(from.outputSources);
        return from.relation;
    }

    private BoundFrom bindFrom(FromSyntax syntax, ReferenceScope scope, boolean outputs) {
        List<SourceBinding> previous = new ArrayList<>(scope.symbols.sources());
        try {
            if (syntax instanceof NamedFromSyntax) {
                return named((NamedFromSyntax) syntax, scope, outputs);
            }
            if (syntax instanceof DerivedFromSyntax) {
                return derived((DerivedFromSyntax) syntax, scope, outputs);
            }
            if (syntax instanceof LateralFromSyntax) {
                return lateral((LateralFromSyntax) syntax, scope, outputs);
            }
            if (syntax instanceof JoinFromSyntax) {
                return join((JoinFromSyntax) syntax, scope, outputs);
            }
            return comma((CommaFromSyntax) syntax, scope, outputs);
        } finally {
            scope.symbols.replaceSources(previous);
        }
    }

    private BoundFrom derived(DerivedFromSyntax syntax, ReferenceScope scope, boolean outputs) {
        String path = syntax.isSyntheticWrapper() ? scope.displayPath
                : scope.displayPath + ".subquery[" + (++scope.childSequence) + "]";
        BoundRelation child = query(syntax.getQuery(), scope, outputs || work.requested(BindingRequirement.CONDITION_FIELDS),
                QueryResult.Role.SUBQUERY, scope.prefix, null, path);
        return instance(syntax, child, new DefinitionId(work.id(syntax, scope, "definition").getValue()),
                new QualifiedName(Collections.singletonList(SemanticNames.normalize(syntax.getAlias().getValue()))),
                syntax.getColumnAliases(), null, scope);
    }

    private BoundFrom lateral(LateralFromSyntax syntax, ReferenceScope scope, boolean outputs) {
        BoundFrom input = bindFrom(syntax.getInput(), scope, outputs);
        BoundRelation result = input.relation;
        List<SourceBinding> visible = new ArrayList<>(input.outputSources);
        scope.symbols.registerAll(visible);
        for (GenerateSyntax generator : syntax.getGenerators()) {
            BoundFrom generated = generate(generator, syntax, result, scope);
            visible.addAll(generated.outputSources);
            scope.symbols.registerAll(generated.outputSources);
            result = generated.relation;
        }
        return new BoundFrom(result, visible, visible);
    }

    private BoundFrom generate(GenerateSyntax generator, LateralFromSyntax syntax, BoundRelation input, ReferenceScope scope) {
        Expressions.Call call = expressions.call(generator.getFunction(), scope);
        List<OutputSlot> generated = new ArrayList<>();
        List<SlotId> ids = new ArrayList<>();
        int index = 0;
        for (IdentifierPart alias : generator.getColumnAliases()) {
            SlotId slot = new SlotId(work.id(generator.getFunction(), scope, "generated-" + index++).getValue());
            generated.add(new OutputSlot(slot, SemanticNames.normalize(alias.getValue()), TypeRef.unknown(), null));
            ids.add(slot);
        }
        NodeId id = work.id(generator.getFunction(), scope, "generate");
        Relations.Generate relation = new Relations.Generate(id,
                BindingWork.combined(input.getOutputSchema(), OutputSchema.known(generated), true), scope.block,
                order(syntax), generator.getFunction().getSourceRef(), input.getId(), call.getFunction(),
                call.getArguments(), ids, BindingWork.mappings(input.getOutputSchema()), generator.isOuter());
        work.put(relation);
        RelationInstanceId instance = new RelationInstanceId(id.getValue() + "/instance");
        QualifiedName alias = new QualifiedName(Collections.singletonList(SemanticNames.normalize(generator.getTableAlias().getValue())));
        work.relationReferences.put(instance, new BoundRelationReference(instance, alias, Optional.<ObjectId>empty(),
                Optional.<DefinitionId>empty(), scope.block));
        return BoundFrom.source(relation, SourceBinding.generated(id, instance, alias,
                generator.getFunction().getSourceRef(), OutputSchema.known(generated)));
    }

    private BoundFrom join(JoinFromSyntax syntax, ReferenceScope scope, boolean outputs) {
        List<SourceBinding> preceding = new ArrayList<>(scope.symbols.sources());
        BoundFrom left = bindFrom(syntax.getOperands().getLeft(), scope, outputs);
        scope.symbols.registerAll(left.outputSources);
        BoundFrom right = bindFrom(syntax.getOperands().getRight(), scope, outputs);
        Relations.JoinType type = Relations.JoinType.valueOf(syntax.getJoinType().name().replace("_OUTER", ""));
        boolean leftOnly = leftOnly(type);
        BoundFrom.Visibility visibility = BoundFrom.Visibility.join(left, right, leftOnly);
        visibility.exposeConditions(scope.symbols, preceding);
        scope.clauseLocation = ClauseLocation.ON;
        BoundExpression condition = joinCondition(syntax, scope, outputs);
        List<Relations.JoinPredicate> predicates = condition == null || condition instanceof Expressions.NotRequestedExpression
                ? Collections.<Relations.JoinPredicate>emptyList()
                : Collections.singletonList(new Relations.JoinPredicate(condition, Relations.PredicateOrigin.ON,
                expressions.owners(condition, scope), syntax.getCondition().getOn().getSourceRef().getSpan().getStart()));
        scope.clauseLocation = ClauseLocation.SELECT;
        Relations.Join relation = new Relations.Join(work.id(syntax, scope, "join"),
                leftOnly ? left.relation.getOutputSchema()
                        : BindingWork.combined(left.relation.getOutputSchema(), right.relation.getOutputSchema(), outputs),
                scope.block, order(syntax), syntax.getSourceRef(), left.relation.getId(), right.relation.getId(),
                type, Relations.JoinForm.EXPLICIT, condition,
                leftOnly ? BindingWork.mappings(left.relation.getOutputSchema()) : joinedMappings(left.relation, right.relation), predicates);
        work.put(relation);
        return new BoundFrom(relation, visibility);
    }

    private BoundExpression joinCondition(JoinFromSyntax syntax, ReferenceScope scope, boolean outputs) {
        SyntaxExpression on = syntax.getCondition().getOn();
        if (on == null) {
            return null;
        }
        return !outputs && !work.requested(BindingRequirement.CONDITION_FIELDS)
                ? objectPredicate(on, scope, BindingRequirement.CONDITION_FIELDS) : expressions.bind(on, scope);
    }

    private BoundFrom comma(CommaFromSyntax syntax, ReferenceScope scope, boolean outputs) {
        BoundRelation result = null;
        List<SourceBinding> visible = new ArrayList<>();
        int index = 0;
        for (FromSyntax source : syntax.getSources()) {
            BoundFrom right = bindFrom(source, scope, outputs);
            visible.addAll(right.outputSources);
            scope.symbols.registerAll(right.outputSources);
            if (result == null) {
                result = right.relation;
            } else {
                result = new Relations.Join(work.id(syntax, scope, "comma-" + index),
                        BindingWork.combined(result.getOutputSchema(), right.relation.getOutputSchema(), outputs),
                        scope.block, order(syntax), syntax.getSourceRef(), result.getId(), right.relation.getId(), Relations.JoinType.CROSS,
                        Relations.JoinForm.COMMA, null, joinedMappings(result, right.relation), Collections.<Relations.JoinPredicate>emptyList());
                work.put(result);
            }
            index++;
        }
        return new BoundFrom(result, visible, visible);
    }

    private BoundFrom named(NamedFromSyntax syntax, ReferenceScope scope, boolean outputs) {
        String simple = SemanticNames.last(syntax.getName());
        List<String> parts = SemanticNames.parts(syntax.getName());
        if (parts.size() == 1 && scope.ctes.containsKey(simple)) {
            CteSyntax cte = scope.ctes.get(simple);
            BoundRelation child = cte(cte, scope, outputs || work.requested(BindingRequirement.CONDITION_FIELDS));
            String alias = syntax.getAlias() == null ? simple : SemanticNames.normalize(syntax.getAlias().getValue());
            return instance(syntax, child, new DefinitionId(cte.getNodeId().getValue() + "/definition"),
                    new QualifiedName(Collections.singletonList(alias)), cte.getColumnAliases(), null, scope);
        }
        CatalogObjectResolver.Resolution resolved = new CatalogObjectResolver(work.context).resolve(syntax.getName(), scope.database, true);
        resolved.requireAvailable();
        DefinitionExpander.Source definition = definitions.discover(resolved, parts.size() == 1, simple, syntax);
        work.declare(resolved.object, resolved.name, definition.kind, SchemaState.NOT_REQUESTED,
                Collections.<FieldIdentity>emptyList(), definition.reference == null ? null : definition.reference.getDefinitionId());
        if (definition.reference != null) {
            DefinitionExpander.Result expansion = definitions.expand(definition.reference, scope, syntax,
                    outputs || work.requested(BindingRequirement.CONDITION_FIELDS), definition.prepared());
            if (expansion.status == DefinitionExpander.Result.Status.EXPANDED) {
                String alias = syntax.getAlias() == null ? simple : SemanticNames.normalize(syntax.getAlias().getValue());
                return instance(syntax, expansion.relation(), definition.reference.getDefinitionId(), new QualifiedName(Collections.singletonList(alias)),
                        definitions.columns(definition.reference, definition.prepared()), definition.isLegacyCte() ? null : resolved.object, scope);
            }
        }
        return scan(syntax, scope, resolved, simple);
    }

    private BoundFrom scan(NamedFromSyntax syntax, ReferenceScope scope,
                           CatalogObjectResolver.Resolution resolved, String simple) {
        ObjectId object = resolved.object;
        QualifiedName name = resolved.name;
        boolean uncertain = resolved.uncertain;
        NodeId id = work.id(syntax, scope, "scan");
        RelationInstanceId instance = new RelationInstanceId(id.getValue() + "/instance");
        VersionRef version = dataVersion(object);
        OutputSchema initialSchema = uncertain ? BindingWork.unknown(new NodeId(id.getValue() + "/uncertain-width"), "SESSION_OBJECT_UNCERTAIN")
                : OutputSchema.notRequested();
        Relations.Scan relation = new Relations.Scan(id, initialSchema, scope.block, order(syntax),
                syntax.getSourceRef(), object, version, instance);
        work.put(relation);
        List<String> aliases = new ArrayList<>();
        aliases.add(simple);
        aliases.add(name.getDisplayName());
        if (syntax.getAlias() != null) {
            aliases.add(SemanticNames.normalize(syntax.getAlias().getValue()));
        }
        SourceBinding binding = SourceBinding.physical(id, instance, object, name, aliases, syntax.getSourceRef(),
                initialSchema, uncertain, syntax.getAlias() == null ? null : SemanticNames.normalize(syntax.getAlias().getValue()));
        String display = syntax.getAlias() == null ? simple : SemanticNames.normalize(syntax.getAlias().getValue());
        work.relationReferences.put(instance, new BoundRelationReference(instance,
                new QualifiedName(Collections.singletonList(display)), Optional.of(object), Optional.<DefinitionId>empty(), scope.block));
        return BoundFrom.source(relation, binding);
    }

    private BoundRelation cte(CteSyntax cte, ReferenceScope scope, boolean outputs) {
        NodeId cacheKey = new NodeId(scope.prefix + cte.getNodeId().getValue());
        BoundRelation cached = work.cteRoots.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (!work.activeDefinitions.add(cacheKey)) {
            throw new BindingFailure("RECURSIVE_DEFINITION", "Recursive CTE is not supported");
        }
        String path = "root".equals(scope.displayPath) ? "cte." + SemanticNames.normalize(cte.getName().getValue())
                : scope.displayPath + ".cte." + SemanticNames.normalize(cte.getName().getValue());
        BoundRelation result = query(cte.getQuery(), scope, outputs, QueryResult.Role.CTE, scope.prefix, null, path);
        work.activeDefinitions.remove(cacheKey);
        work.cteRoots.put(cacheKey, result);
        return result;
    }

    private BoundFrom instance(FromSyntax syntax, BoundRelation input, DefinitionId definition, QualifiedName alias,
                                   List<IdentifierPart> columnAliases, ObjectId physicalObject, ReferenceScope scope) {
        NodeId id = work.id(syntax, scope, "instance");
        RelationInstanceId instance = new RelationInstanceId(id.getValue() + "/relation");
        List<OutputSlot> slots = new ArrayList<>();
        List<SlotMapping> mappings = new ArrayList<>();
        int index = 0;
        for (OutputSlot inputSlot : BindingWork.slots(input.getOutputSchema())) {
            String name = index < columnAliases.size() ? SemanticNames.normalize(columnAliases.get(index).getValue()) : inputSlot.getDisplayName();
            FieldIdentity field = physicalObject == null ? null : work.field(physicalObject, name);
            if (field != null) {
                work.declareField(physicalObject, field);
            }
            OutputSlot slot = new OutputSlot(new SlotId(id.getValue() + "/output-" + index++), name, inputSlot.getType(), field);
            slots.add(slot);
            mappings.add(new SlotMapping(slot.getId(), inputSlot.getId()));
        }
        OutputSchema schema = input.getOutputSchema().getState() == SchemaState.KNOWN ? OutputSchema.known(slots)
                : input.getOutputSchema().getState() == SchemaState.NOT_REQUESTED ? OutputSchema.notRequested()
                  : partialSchema(slots, new NodeId(id.getValue() + "/width"), "DEFINITION_WIDTH_UNKNOWN");
        Relations.DefinitionInstance relation = new Relations.DefinitionInstance(id, schema, scope.block, order(syntax),
                syntax.getSourceRef(), input.getId(), definition, instance, dataVersion(physicalObject), mappings, physicalObject);
        work.put(relation);
        SourceBinding binding = SourceBinding.derived(id, instance, physicalObject, alias, syntax.getSourceRef(), schema);
        work.relationReferences.put(instance, new BoundRelationReference(instance, alias, Optional.ofNullable(physicalObject),
                Optional.of(definition), scope.block));
        return BoundFrom.source(relation, binding);
    }

    private VersionRef dataVersion(ObjectId object) {
        if (!work.requested(BindingRequirement.DATASET_VERSIONS)) {
            return VersionRef.notRequested();
        }
        if (object == null) {
            return VersionRef.unknown("LOGICAL_DEFINITION_NOT_MATERIALIZED");
        }
        BoundObjectReference declaration = work.objects.get(object);
        if (declaration != null && declaration.getKind() == BoundObjectReference.ObjectKind.VIEW) {
            return VersionRef.unknown("VIEW_NOT_MATERIALIZED");
        }
        if (work.context.getSession().getDatasetVersions().containsKey(object)) {
            return work.context.getSession().getDatasetVersions().get(object).getVersion();
        }
        return VersionRef.known(new DatasetVersionId(object.getValue() + "/session/"
                + work.context.getSession().getInitialContext().getSessionId() + "/script-entry"));
    }

    private OutputSchema partialSchema(List<OutputSlot> slots, NodeId id, String code) {
        List<OutputSegment> segments = new ArrayList<>();
        if (!slots.isEmpty()) {
            segments.add(new KnownOutputSegment(slots));
        }
        segments.add(new UnknownOutputSegment(id, code));
        return new OutputSchema(SchemaState.UNKNOWN, segments);
    }

    private BoundRelation commaConditions(BoundRelation relation, SyntaxExpression where, ReferenceScope scope) {
        if (!(relation instanceof Relations.Join)) {
            return relation;
        }
        List<SyntaxExpression> conditions = new ArrayList<>();
        conjuncts(where, conditions);
        List<Relations.JoinPredicate> predicates = new ArrayList<>();
        for (SyntaxExpression syntax : conditions) {
            BoundExpression expression = expressions.bind(syntax, scope);
            if (expressions.correlated(expression)) {
                continue;
            }
            List<RelationInstanceId> owners = expressions.owners(expression, scope);
            Set<RelationInstanceId> localOwners = new LinkedHashSet<>();
            for (SourceBinding source : scope.symbols.sources()) {
                localOwners.add(source.instanceId);
            }
            owners.retainAll(localOwners);
            if (owners.size() > 1) {
                predicates.add(new Relations.JoinPredicate(expression, Relations.PredicateOrigin.WHERE, owners,
                        syntax.getSourceRef().getSpan().getStart()));
            }
        }
        Relations.Join join = (Relations.Join) relation;
        Relations.Join replacement = new Relations.Join(join.getId(), join.getOutputSchema(), join.getQueryBlockId(),
                join.getSourceOrder(), join.getSourceRef().orElse(null), join.getLeft(), join.getRight(), join.getJoinType(),
                join.getJoinForm(), null, join.getMappings(), predicates);
        work.put(replacement);
        return replacement;
    }

    private void conjuncts(SyntaxExpression expression, List<SyntaxExpression> result) {
        if (expression instanceof BinarySyntax && ((BinarySyntax) expression).getOperator() == BinaryOperatorSyntax.AND) {
            conjuncts(((BinarySyntax) expression).getLeft(), result);
            conjuncts(((BinarySyntax) expression).getRight(), result);
        } else {
            result.add(expression);
        }
    }

    private List<SourceBinding> starSources(StarSyntax star, ReferenceScope scope) {
        return star.getQualifier() == null ? scope.symbols.sources()
                : scope.symbols.qualified(String.join(".", SemanticNames.parts(star.getQualifier())));
    }

    private String outputName(SelectItemSyntax item, int position) {
        if (item.getAlias() != null) {
            return SemanticNames.normalize(item.getAlias().getValue());
        }
        if (item.getExpression() instanceof NameRefSyntax) {
            return SemanticNames.last(((NameRefSyntax) item.getExpression()).getName());
        }
        return "_col" + position;
    }

    private BoundRelation tail(BoundRelation relation, QueryTailSyntax tail, SyntaxQuery syntax, ReferenceScope scope) {
        List<Relations.OrderKey> orders = clauseOrders(tail, scope);
        return tail(relation, tail, syntax, scope, orders);
    }

    private List<Relations.OrderKey> clauseOrders(QueryTailSyntax tail, ReferenceScope scope) {
        ClauseLocation previous = scope.clauseLocation;
        scope.clauseLocation = ClauseLocation.ORDER_BY;
        List<Relations.OrderKey> orders = expressions.orders(tail.getOrderBy(), scope);
        scope.clauseLocation = ClauseLocation.SORT_BY;
        orders.addAll(expressions.orders(tail.getSortBy(), scope));
        scope.clauseLocation = previous;
        return orders;
    }

    private void distributionOutputs(SyntaxQuery syntax, QueryTailSyntax tail, ReferenceScope scope,
                                     Map<SlotId, BoundExpression> outputExpressions, ProjectionLayout.Builder layout) {
        int index = 0;
        scope.clauseLocation = ClauseLocation.DISTRIBUTE_BY;
        for (SyntaxExpression key : tail.getDistributeBy()) {
            BoundExpression expression = OutputAliasExpander.expand(expressions.bind(key, scope), outputExpressions);
            hiddenOutput(syntax, scope, "distribute-" + index++, expression, layout);
        }
        index = 0;
        scope.clauseLocation = ClauseLocation.CLUSTER_BY;
        for (SyntaxExpression key : tail.getClusterBy()) {
            BoundExpression expression = OutputAliasExpander.expand(expressions.bind(key, scope), outputExpressions);
            hiddenOutput(syntax, scope, "cluster-" + index++, expression, layout);
        }
        scope.clauseLocation = ClauseLocation.SELECT;
    }

    private BoundRelation tail(BoundRelation relation, QueryTailSyntax tail, SyntaxQuery syntax, ReferenceScope scope,
                               List<Relations.OrderKey> orders) {
        if (!orders.isEmpty()) {
            relation = new Relations.Sort(work.id(syntax, scope, "sort"), relation.getOutputSchema(), scope.block, order(syntax),
                    syntax.getSourceRef(), relation.getId(), orders, BindingWork.mappings(relation.getOutputSchema()));
            work.put(relation);
        }
        if (tail.getLimit() != null) {
            relation = new Relations.Limit(work.id(syntax, scope, "limit"), relation.getOutputSchema(), scope.block, order(syntax),
                    syntax.getSourceRef(), relation.getId(), expressions.bind(tail.getLimit().getCount(), scope),
                    tail.getLimit().getOffset() == null ? null : expressions.bind(tail.getLimit().getOffset(), scope),
                    BindingWork.mappings(relation.getOutputSchema()));
            work.put(relation);
        }
        return relation;
    }

    private SlotId hiddenOutput(SyntaxQuery syntax, ReferenceScope scope, String role, BoundExpression expression,
                                ProjectionLayout.Builder layout) {
        SlotId slot = new SlotId(work.id(syntax, scope, role + "-output").getValue());
        return layout.addHidden(new OutputSlot(slot, "_hidden_" + role, expression.getType(), null), expression);
    }

    private BoundExpression hiddenReference(SyntaxQuery syntax, ReferenceScope scope, String role,
                                            BoundExpression expression, SlotId slot) {
        return new Expressions.SlotRef(work.id(syntax, scope, role + "-reference"), expression.getType(),
                expression.getSourceRef().orElse(null), slot, Collections.<String>emptyList());
    }

    private boolean leftOnly(Relations.JoinType type) {
        return type == Relations.JoinType.LEFT_SEMI || type == Relations.JoinType.LEFT_ANTI;
    }

    private boolean aggregate(BoundExpression expression) {
        if (expression instanceof Expressions.Call) {
            Expressions.Call call = (Expressions.Call) expression;
            if (call.getFunction().getKind() == FunctionSymbol.FunctionKind.AGGREGATE) {
                return true;
            }
        }
        for (BoundExpression child : com.panfan.hivelineage.v2.ir.IrTraversal.children(expression)) {
            if (aggregate(child)) {
                return true;
            }
        }
        return false;
    }

    private BoundExpression objectPredicate(SyntaxExpression expression, ReferenceScope scope, BindingRequirement requirement) {
        List<NodeId> roots = new ArrayList<>();
        collectSubqueryRoots(expression, scope, roots);
        return new Expressions.NotRequestedExpression(work.id(expression, scope, "not-requested"),
                TypeRef.unknown(), expression.getSourceRef(), requirement, roots);
    }

    private void collectSubqueryRoots(SyntaxExpression expression, ReferenceScope scope, List<NodeId> roots) {
        new SyntaxSubqueryCollector(work, this).collect(expression, scope, roots);
    }

    private List<SlotMapping> joinedMappings(BoundRelation left, BoundRelation right) {
        List<SlotMapping> mappings = BindingWork.mappings(left.getOutputSchema());
        mappings.addAll(BindingWork.mappings(right.getOutputSchema()));
        return mappings;
    }

    private BoundRelation refresh(NodeId id, Set<NodeId> visited) {
        BoundRelation relation = work.relations.get(id);
        if (!visited.add(id)) {
            return relation;
        }
        for (NodeId input : relation.getInputIds()) {
            refresh(input, visited);
        }
        BoundRelation replacement = relation;
        if (relation instanceof Relations.Filter) {
            Relations.Filter filter = (Relations.Filter) relation;
            BoundRelation input = work.relations.get(filter.getInput());
            replacement = new Relations.Filter(filter.getId(), input.getOutputSchema(), filter.getQueryBlockId(),
                    filter.getSourceOrder(), filter.getSourceRef().orElse(null), input.getId(), filter.getPredicate(),
                    BindingWork.mappings(input.getOutputSchema()));
        } else if (relation instanceof Relations.Join) {
            Relations.Join join = (Relations.Join) relation;
            BoundRelation left = work.relations.get(join.getLeft());
            BoundRelation right = work.relations.get(join.getRight());
            replacement = new Relations.Join(join.getId(), leftOnly(join.getJoinType()) ? left.getOutputSchema()
                    : BindingWork.combined(left.getOutputSchema(), right.getOutputSchema(), true),
                    join.getQueryBlockId(), join.getSourceOrder(), join.getSourceRef().orElse(null), join.getLeft(), join.getRight(),
                    join.getJoinType(), join.getJoinForm(), join.getCondition().orElse(null),
                    leftOnly(join.getJoinType()) ? BindingWork.mappings(left.getOutputSchema()) : joinedMappings(left, right), join.getPredicates());
        } else if (relation instanceof Relations.Generate) {
            Relations.Generate generate = (Relations.Generate) relation;
            BoundRelation input = work.relations.get(generate.getInput());
            List<OutputSlot> generated = new ArrayList<>();
            for (OutputSlot slot : BindingWork.slots(generate.getOutputSchema())) {
                if (generate.getGeneratedSlots().contains(slot.getId())) {
                    generated.add(slot);
                }
            }
            replacement = new Relations.Generate(generate.getId(),
                    BindingWork.combined(input.getOutputSchema(), OutputSchema.known(generated), true), generate.getQueryBlockId(),
                    generate.getSourceOrder(), generate.getSourceRef().orElse(null), input.getId(), generate.getFunction(),
                    generate.getArguments(), generate.getGeneratedSlots(), BindingWork.mappings(input.getOutputSchema()), generate.getOuter());
        }
        work.put(replacement);
        return replacement;
    }

    private int order(com.panfan.hivelineage.v2.syntax.SyntaxNode syntax) {
        return syntax.getSourceRef().getSpan().getStart();
    }
}
