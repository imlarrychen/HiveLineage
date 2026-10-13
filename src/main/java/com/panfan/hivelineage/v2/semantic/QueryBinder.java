package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.DefinitionPolicy;
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
import com.panfan.hivelineage.v2.metadata.DefinitionRef;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.LookupStatus;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;
import com.panfan.hivelineage.v2.session.CatalogEntry;
import com.panfan.hivelineage.v2.session.DefinitionRequest;
import com.panfan.hivelineage.v2.session.LogicalDefinition;
import com.panfan.hivelineage.v2.session.OverlayEntry;
import com.panfan.hivelineage.v2.syntax.BinaryOperatorSyntax;
import com.panfan.hivelineage.v2.syntax.BinarySyntax;
import com.panfan.hivelineage.v2.syntax.CommaFromSyntax;
import com.panfan.hivelineage.v2.syntax.CteSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;
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
import com.panfan.hivelineage.v2.syntax.QueryStatementSyntax;
import com.panfan.hivelineage.v2.syntax.QueryTailSyntax;
import com.panfan.hivelineage.v2.syntax.SelectBodySyntax;
import com.panfan.hivelineage.v2.syntax.SelectItemSyntax;
import com.panfan.hivelineage.v2.syntax.SelectQuerySyntax;
import com.panfan.hivelineage.v2.syntax.SetQuerySyntax;
import com.panfan.hivelineage.v2.syntax.StarSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.SyntaxQuery;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
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

    QueryBinder(BindingWork work) {
        this.work = work;
        this.expressions = new ExpressionBinder(work);
        this.references = new ReferenceBinder(work);
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
            scope.clauseLocation = "where";
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
            scope.clauseLocation = "select";
        }
        if (!outputs) {
            input = refresh(input.getId(), new LinkedHashSet<NodeId>());
            List<NodeId> projectionQueries = new ArrayList<>();
            for (SelectItemSyntax item : body.getSelectItems()) {
                collectSubqueryRoots(item.getExpression(), scope, projectionQueries);
            }
            if (!projectionQueries.isEmpty()) {
                BoundExpression dependency = new Expressions.NotRequestedExpression(work.id(syntax, scope, "select-not-requested"),
                    TypeRef.unknown(), syntax.getSourceRef(), BindingRequirement.OUTPUT_SCHEMA, projectionQueries);
                input = new Relations.Filter(work.id(syntax, scope, "select-dependencies"), input.getOutputSchema(), scope.block,
                    order(syntax), syntax.getSourceRef(), input.getId(), dependency, BindingWork.mappings(input.getOutputSchema()));
                work.put(input);
            }
            if (clauses.getHaving() != null) {
                BoundExpression having = objectPredicate(clauses.getHaving(), scope, BindingRequirement.CONDITION_FIELDS);
                input = new Relations.Filter(work.id(syntax, scope, "having"), input.getOutputSchema(), scope.block, order(syntax),
                    clauses.getHaving().getSourceRef(), input.getId(), having, BindingWork.mappings(input.getOutputSchema()));
                work.put(input);
            }
            BoundRelation projection = new Relations.Project(work.id(syntax, scope, "project"), OutputSchema.notRequested(),
                scope.block, order(syntax), syntax.getSourceRef(), input.getId(), Collections.<OutputAssignment>emptyList());
            work.put(projection);
            return projection;
        }
        List<OutputSegment> segments = new ArrayList<>();
        List<OutputAssignment> assignments = new ArrayList<>();
        int position = 0;
        for (SelectItemSyntax item : body.getSelectItems()) {
            if (item.getExpression() instanceof StarSyntax) {
                StarSyntax star = (StarSyntax) item.getExpression();
                List<SourceBinding> selected = starSources(star, scope);
                if (selected.isEmpty()) {
                    segments.add(new UnknownOutputSegment(work.id(star, scope, "star"), "UNRESOLVED_STAR"));
                    work.warn("UNRESOLVED_STAR", "Star owner is unavailable", star.getSourceRef());
                }
                for (SourceBinding source : selected) {
                    references.loadSchema(source, scope);
                    if (source.schema.getState() != SchemaState.KNOWN) {
                        segments.add(new UnknownOutputSegment(work.id(star, scope, "star-" + source.instanceId.getValue()), "SCHEMA_UNKNOWN"));
                        continue;
                    }
                    List<OutputSlot> expanded = new ArrayList<>();
                    for (OutputSlot inputSlot : BindingWork.slots(source.schema)) {
                        OutputSlot output = new OutputSlot(new SlotId(work.id(star, scope, "output-" + position++).getValue()),
                            inputSlot.getDisplayName(), inputSlot.getType(), null);
                        expanded.add(output);
                        assignments.add(new OutputAssignment(output.getId(), new Expressions.SlotRef(
                            new NodeId(output.getId().getValue() + "/reference"), inputSlot.getType(), star.getSourceRef(),
                            inputSlot.getId(), Collections.<String>emptyList())));
                    }
                    segments.add(new KnownOutputSegment(expanded));
                }
            } else {
                BoundExpression expression = expressions.bind(item.getExpression(), scope);
                String name = outputName(item, position);
                OutputSlot output = new OutputSlot(new SlotId(work.id(item.getExpression(), scope, "output-" + position++).getValue()),
                    name, work.types.analyze(expression, work.context.getFunctions()).getType(), null);
                segments.add(new KnownOutputSegment(Collections.singletonList(output)));
                assignments.add(new OutputAssignment(output.getId(), expression));
            }
        }
        input = refresh(input.getId(), new LinkedHashSet<NodeId>());
        if (!scope.windowComputations.isEmpty()) {
            OutputSchema windowSchema = BindingWork.combined(input.getOutputSchema(), OutputSchema.known(scope.windowSlots), true);
            BoundRelation window = new Relations.Window(work.id(syntax, scope, "window"), windowSchema, scope.block,
                order(syntax), syntax.getSourceRef(), input.getId(), BindingWork.mappings(input.getOutputSchema()), scope.windowComputations);
            work.put(window);
            input = window;
        }
        SchemaState state = SchemaState.KNOWN;
        for (OutputSegment segment : segments) {
            if (segment instanceof UnknownOutputSegment) {
                state = SchemaState.UNKNOWN;
            }
        }
        OutputSchema schema = new OutputSchema(state, segments);
        List<BoundExpression> groupings = new ArrayList<>();
        if (clauses.getGrouping() != null) {
            for (List<SyntaxExpression> group : clauses.getGrouping().getSets()) {
                for (SyntaxExpression expression : group) {
                    groupings.add(expressions.bind(expression, scope));
                }
            }
        }
        boolean aggregate = !groupings.isEmpty();
        for (OutputAssignment assignment : assignments) {
            aggregate |= aggregate(assignment.getExpression());
        }
        BoundRelation projection = aggregate
            ? new Relations.Aggregate(work.id(syntax, scope, "aggregate"), schema, scope.block, order(syntax),
                syntax.getSourceRef(), input.getId(), groupings, assignments)
            : new Relations.Project(work.id(syntax, scope, "project"), schema, scope.block, order(syntax),
                syntax.getSourceRef(), input.getId(), assignments);
        work.put(projection);
        scope.queryOutputs.addAll(BindingWork.slots(projection.getOutputSchema()));
        if (clauses.getHaving() != null) {
            scope.clauseLocation = "having";
            BoundExpression predicate = expressions.bind(clauses.getHaving(), scope);
            projection = new Relations.Filter(work.id(syntax, scope, "having"), schema, scope.block, order(syntax),
                clauses.getHaving().getSourceRef(), projection.getId(), predicate, BindingWork.mappings(schema));
            work.put(projection);
            scope.clauseLocation = "select";
        }
        if (body.getQuantifier() == QuantifierSyntax.DISTINCT) {
            List<SlotId> keys = new ArrayList<>();
            for (OutputSlot slot : BindingWork.slots(schema)) {
                keys.add(slot.getId());
            }
            projection = new Relations.Distinct(work.id(syntax, scope, "distinct"), schema, scope.block, order(syntax),
                syntax.getSourceRef(), projection.getId(), keys, BindingWork.mappings(schema));
            work.put(projection);
        }
        return tail(projection, clauses.getTail(), syntax, scope);
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
        return outputs ? tail(result, syntax.getBody().getTail(), syntax, scope) : result;
    }

    BoundRelation from(FromSyntax syntax, ReferenceScope scope, boolean outputs) {
        if (syntax instanceof NamedFromSyntax) {
            return named((NamedFromSyntax) syntax, scope, outputs);
        }
        if (syntax instanceof DerivedFromSyntax) {
            DerivedFromSyntax derived = (DerivedFromSyntax) syntax;
            String path = derived.isSyntheticWrapper() ? scope.displayPath
                : scope.displayPath + ".subquery[" + (++scope.childSequence) + "]";
            BoundRelation child = query(derived.getQuery(), scope, outputs || work.requested(BindingRequirement.CONDITION_FIELDS),
                QueryResult.Role.SUBQUERY, scope.prefix, null, path);
            return instance(syntax, child, new DefinitionId(work.id(syntax, scope, "definition").getValue()),
                new QualifiedName(Collections.singletonList(SemanticNames.normalize(derived.getAlias().getValue()))),
                derived.getColumnAliases(), null, scope);
        }
        if (syntax instanceof LateralFromSyntax) {
            LateralFromSyntax lateral = (LateralFromSyntax) syntax;
            BoundRelation result = from(lateral.getInput(), scope, outputs);
            for (GenerateSyntax generator : lateral.getGenerators()) {
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
                    BindingWork.combined(result.getOutputSchema(), OutputSchema.known(generated), true), scope.block,
                    order(syntax), generator.getFunction().getSourceRef(), result.getId(), call.getFunction(),
                    call.getArguments(), ids, BindingWork.mappings(result.getOutputSchema()), generator.isOuter());
                work.put(relation);
                RelationInstanceId instance = new RelationInstanceId(id.getValue() + "/instance");
                QualifiedName alias = new QualifiedName(Collections.singletonList(SemanticNames.normalize(generator.getTableAlias().getValue())));
                scope.sources.add(new SourceBinding(id, instance, null, alias, alias.getParts(), false,
                    generator.getFunction().getSourceRef(), OutputSchema.known(generated), true));
                scope.sources.get(scope.sources.size() - 1).generated = true;
                work.relationReferences.put(instance, new BoundRelationReference(instance, alias, Optional.<ObjectId>empty(),
                    Optional.<DefinitionId>empty(), scope.block));
                result = relation;
            }
            return result;
        }
        if (syntax instanceof JoinFromSyntax) {
            JoinFromSyntax join = (JoinFromSyntax) syntax;
            BoundRelation left = from(join.getOperands().getLeft(), scope, outputs);
            BoundRelation right = from(join.getOperands().getRight(), scope, outputs);
            scope.clauseLocation = "on";
            BoundExpression condition = join.getCondition().getOn() == null ? null
                : !outputs && !work.requested(BindingRequirement.CONDITION_FIELDS)
                    ? objectPredicate(join.getCondition().getOn(), scope, BindingRequirement.CONDITION_FIELDS)
                    : expressions.bind(join.getCondition().getOn(), scope);
            List<Relations.JoinPredicate> predicates = condition == null || condition instanceof Expressions.NotRequestedExpression
                ? Collections.<Relations.JoinPredicate>emptyList()
                : Collections.singletonList(new Relations.JoinPredicate(condition, Relations.PredicateOrigin.ON,
                    expressions.owners(condition, scope), join.getCondition().getOn().getSourceRef().getSpan().getStart()));
            scope.clauseLocation = "select";
            Relations.Join relation = new Relations.Join(work.id(syntax, scope, "join"),
                BindingWork.combined(left.getOutputSchema(), right.getOutputSchema(), outputs), scope.block, order(syntax),
                syntax.getSourceRef(), left.getId(), right.getId(),
                Relations.JoinType.valueOf(join.getJoinType().name().replace("_OUTER", "")),
                Relations.JoinForm.EXPLICIT, condition, joinedMappings(left, right), predicates);
            work.put(relation);
            return relation;
        }
        CommaFromSyntax comma = (CommaFromSyntax) syntax;
        BoundRelation result = null;
        int index = 0;
        for (FromSyntax source : comma.getSources()) {
            BoundRelation right = from(source, scope, outputs);
            if (result == null) {
                result = right;
            } else {
                result = new Relations.Join(work.id(syntax, scope, "comma-" + index),
                    BindingWork.combined(result.getOutputSchema(), right.getOutputSchema(), outputs),
                    scope.block, order(syntax), syntax.getSourceRef(), result.getId(), right.getId(), Relations.JoinType.CROSS,
                    Relations.JoinForm.COMMA, null, joinedMappings(result, right), Collections.<Relations.JoinPredicate>emptyList());
                work.put(result);
            }
            index++;
        }
        return result;
    }

    private BoundRelation named(NamedFromSyntax syntax, ReferenceScope scope, boolean outputs) {
        String simple = SemanticNames.last(syntax.getName());
        List<String> parts = SemanticNames.parts(syntax.getName());
        if (parts.size() == 1 && scope.ctes.containsKey(simple)) {
            CteSyntax cte = scope.ctes.get(simple);
            BoundRelation child = cte(cte, scope, outputs || work.requested(BindingRequirement.CONDITION_FIELDS));
            String alias = syntax.getAlias() == null ? simple : SemanticNames.normalize(syntax.getAlias().getValue());
            return instance(syntax, child, new DefinitionId(cte.getNodeId().getValue() + "/definition"),
                new QualifiedName(Collections.singletonList(alias)), cte.getColumnAliases(), null, scope);
        }
        CatalogEntry local = local(parts, simple, scope.database);
        QualifiedName name;
        if (local != null) {
            name = local.getObject().getName();
        } else {
            if (parts.size() == 1) {
                if (!scope.database.isPresent() || work.context.getSession().getUncertainty().isDatabaseUncertain()) {
                    throw new BindingFailure("DATABASE_NOT_RESOLVED", "Unqualified source requires a current database");
                }
            }
            name = SemanticNames.qualify(parts, scope.database);
        }
        LogicalDefinition logical = null;
        if (parts.size() == 1 && work.context.getContract().getDefinitionPolicy() == DefinitionPolicy.CURRENT_API_CTE_REUSE) {
            logical = work.context.getSession().getScriptDefinitions().get(new QualifiedName(Collections.singletonList(simple)));
        }
        if (local != null && local.getDefinition().isPresent()) {
            logical = local.getDefinition().get();
        }
        ObjectId object = local == null ? work.objectId(name) : local.getObject().getObjectId();
        BoundObjectReference.ObjectKind kind = local != null && local.getObject().getKind() == ObjectDescriptor.Kind.TEMPORARY_TABLE
            ? BoundObjectReference.ObjectKind.TEMPORARY : local != null && local.getObject().getKind() == ObjectDescriptor.Kind.VIEW
                ? BoundObjectReference.ObjectKind.VIEW : BoundObjectReference.ObjectKind.TABLE;
        DefinitionRef definition = logical == null ? null : logical.getReference();
        OverlayEntry overlay = work.context.getSession().getPersistentOverlay().get(name);
        boolean uncertain = work.context.getSession().getUncertainty().getObjects().contains(object)
            || overlay != null && overlay.getState() == OverlayEntry.State.UNKNOWN
            || local == null && work.context.getSession().getUncertainty().isCatalogUncertain();
        if (uncertain) {
            definition = null;
            work.warn("SESSION_OBJECT_UNCERTAIN", "Prior effects left object state uncertain", syntax.getSourceRef());
        }
        if (!uncertain && work.context.getContract().isExpandViewLineage() && local == null && logical == null) {
            LookupResult<ObjectDescriptor> descriptor = work.context.getMetadata().lookupObject(name, work.context.getMetadataContext());
            work.diagnostics.addAll(descriptor.getDiagnostics().getDiagnostics());
            if (descriptor.getStatus() == LookupStatus.FOUND && descriptor.getValue().get().getKind() == ObjectDescriptor.Kind.VIEW) {
                kind = BoundObjectReference.ObjectKind.VIEW;
                definition = descriptor.getValue().get().getDefinition().orElse(null);
                if (definition == null) {
                    work.warn("VIEW_DEFINITION_NOT_FOUND", "View definition is unavailable", syntax.getSourceRef());
                }
            }
        }
        work.declare(object, name, kind, SchemaState.NOT_REQUESTED, Collections.<FieldIdentity>emptyList(),
            definition == null ? null : definition.getDefinitionId());
        if (definition != null) {
            PreparedDefinition localPrepared = logical != null && logical.getTemplate().isPresent()
                && logical.getTemplate().get() instanceof PreparedDefinition ? (PreparedDefinition) logical.getTemplate().get() : null;
            BoundRelation bound = external(definition, scope, syntax, outputs || work.requested(BindingRequirement.CONDITION_FIELDS), localPrepared);
            if (bound != null) {
                String alias = syntax.getAlias() == null ? simple : SemanticNames.normalize(syntax.getAlias().getValue());
                return instance(syntax, bound, definition.getDefinitionId(), new QualifiedName(Collections.singletonList(alias)),
                    Collections.<IdentifierPart>emptyList(), logical != null && logical.getKind() == LogicalDefinition.Kind.LEGACY_CTE ? null : object, scope);
            }
        }
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
        SourceBinding binding = new SourceBinding(id, instance, object, name, aliases, true, syntax.getSourceRef(),
            initialSchema, uncertain);
        binding.uncertain = uncertain;
        binding.explicitAlias = syntax.getAlias() == null ? null : SemanticNames.normalize(syntax.getAlias().getValue());
        scope.sources.add(binding);
        String display = syntax.getAlias() == null ? simple : SemanticNames.normalize(syntax.getAlias().getValue());
        work.relationReferences.put(instance, new BoundRelationReference(instance,
            new QualifiedName(Collections.singletonList(display)), Optional.of(object), Optional.<DefinitionId>empty(), scope.block));
        return relation;
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

    private CatalogEntry local(List<String> parts, String simple, Optional<QualifiedName> database) {
        for (CatalogEntry entry : work.context.getSession().getTemporaryCatalog().values()) {
            for (QualifiedName alias : entry.getLookupNames()) {
                if (alias.getParts().equals(parts)
                    || (parts.size() == 1 && SemanticNames.normalize(entry.getObject().getName().getParts()
                        .get(entry.getObject().getName().getParts().size() - 1)).equals(simple))) {
                    return entry;
                }
            }
        }
        QualifiedName qualified = parts.size() > 1 ? new QualifiedName(parts)
            : database.isPresent() ? SemanticNames.qualify(parts, database) : null;
        if (qualified != null) {
            OverlayEntry entry = work.context.getSession().getPersistentOverlay().get(qualified);
            if (entry != null && entry.getState() == OverlayEntry.State.DELETED) {
                throw new BindingFailure("OBJECT_NOT_FOUND", "Object was dropped earlier in this script");
            }
            if (entry != null && entry.getEntry().isPresent()) {
                return entry.getEntry().get();
            }
        }
        return null;
    }

    private BoundRelation external(DefinitionRef reference, ReferenceScope scope, NamedFromSyntax source, boolean outputs,
                                   PreparedDefinition localPrepared) {
        if (work.context.getFailedDefinitions().containsKey(reference.getDefinitionId())) {
            work.diagnostics.addAll(work.context.getFailedDefinitions().get(reference.getDefinitionId()).getDiagnostics());
            work.partial = true;
            return null;
        }
        if (work.definitionPath.contains(reference.getDefinitionId())) {
            work.warn("VIEW_EXPANSION_CYCLE", "Definition cycle detected", source.getSourceRef());
            return null;
        }
        if (work.definitionPath.size() >= work.context.getContract().getMaxViewExpandDepth()) {
            work.warn("VIEW_EXPANSION_DEPTH_EXCEEDED", "Definition expansion depth exceeded", source.getSourceRef());
            return null;
        }
        PreparedDefinition prepared = localPrepared == null ? work.context.getDefinitions().get(reference.getDefinitionId()) : localPrepared;
        if (prepared == null) {
            work.definitionRequests.add(new DefinitionRequest(reference, work.demand.getRequirements(), work.definitionPath,
                Math.max(0, work.context.getContract().getMaxViewExpandDepth() - work.definitionPath.size())));
            return null;
        }
        if (prepared.getSyntax().isPresent()) {
            SyntaxStatement statement = prepared.getSyntax().get().getStatement();
            SyntaxQuery query = statement instanceof QueryStatementSyntax ? ((QueryStatementSyntax) statement).getQuery()
                : statement instanceof DefinitionStatementSyntax ? ((DefinitionStatementSyntax) statement).getDefinition().getQuery() : null;
            if (query != null) {
                work.definitionPath.add(reference.getDefinitionId());
                ReferenceScope definitionScope = new ReferenceScope(work, null,
                    new ScopeId(scope.prefix + source.getNodeId().getValue() + "/definition-context"),
                    scope.block, scope.prefix + source.getNodeId().getValue() + "/definition/", Collections.<CteSyntax>emptyList());
                definitionScope.database = reference.getDefinitionContext().getDefaultDatabase();
                definitionScope.analyzeJoins = false;
                BoundRelation result = query(query, definitionScope, outputs, QueryResult.Role.DEFINITION, definitionScope.prefix,
                    null, scope.displayPath + ".definition");
                work.definitionPath.remove(work.definitionPath.size() - 1);
                return result;
            }
        }
        if (prepared.getBoundRelation().isPresent() && prepared.getBoundUnit().isPresent()) {
            for (BoundRelation relation : prepared.getBoundUnit().get().getRelations()) {
                work.put(relation);
            }
            work.objects.putAll(prepared.getBoundUnit().get().getObjects());
            work.scopes.putAll(prepared.getBoundUnit().get().getScopes());
            work.relationReferences.putAll(prepared.getBoundUnit().get().getRelationReferences());
            for (Map.Entry<com.panfan.hivelineage.v2.ir.identity.QueryBlockId, BoundQueryBlockReference> entry
                : prepared.getBoundUnit().get().getQueryBlocks().entrySet()) {
                work.queryBlocks.put(entry.getKey(), new BoundQueryBlockReference(entry.getKey(), entry.getValue().getPath(), false));
            }
            return prepared.getBoundRelation().get();
        }
        work.warn("DEFINITION_STRUCTURE_INCOMPLETE", "Definition has no complete graph or syntax", source.getSourceRef());
        return null;
    }

    private BoundRelation instance(FromSyntax syntax, BoundRelation input, DefinitionId definition, QualifiedName alias,
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
        scope.sources.add(new SourceBinding(id, instance, physicalObject, alias, Collections.singletonList(alias.getDisplayName()),
            false, syntax.getSourceRef(), schema, true));
        work.relationReferences.put(instance, new BoundRelationReference(instance, alias, Optional.ofNullable(physicalObject),
            Optional.of(definition), scope.block));
        return relation;
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
            for (SourceBinding source : scope.sources) {
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
        if (star.getQualifier() == null) {
            return scope.sources;
        }
        String qualifier = String.join(".", SemanticNames.parts(star.getQualifier()));
        List<SourceBinding> sources = new ArrayList<>();
        List<SourceBinding> explicit = new ArrayList<>();
        for (SourceBinding source : scope.sources) {
            if (source.aliases.contains(qualifier)) {
                sources.add(source);
                if (qualifier.equals(source.explicitAlias) || !source.physical) {
                    explicit.add(source);
                }
            }
        }
        return explicit.isEmpty() ? sources : explicit;
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
        if (!tail.getOrderBy().isEmpty() || !tail.getSortBy().isEmpty()) {
            List<Relations.OrderKey> orders = expressions.orders(tail.getOrderBy(), scope);
            orders.addAll(expressions.orders(tail.getSortBy(), scope));
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

    private boolean aggregate(BoundExpression expression) {
        if (expression instanceof Expressions.Call) {
            Expressions.Call call = (Expressions.Call) expression;
            if (call.getFunction().getKind() == FunctionSymbol.FunctionKind.AGGREGATE) {
                return true;
            }
            for (BoundExpression child : call.getArguments()) {
                if (aggregate(child)) {
                    return true;
                }
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
        if (expression instanceof com.panfan.hivelineage.v2.syntax.SubqueryExpressionSyntax) {
            com.panfan.hivelineage.v2.syntax.SubqueryExpressionSyntax subquery = (com.panfan.hivelineage.v2.syntax.SubqueryExpressionSyntax) expression;
            String suffix = subquery.getSubqueryKind().name().toLowerCase(java.util.Locale.ROOT);
            String path = scope.displayPath + "." + scope.clauseLocation + "." + suffix + "[" + (++scope.childSequence) + "]";
            BoundRelation relation = query(subquery.getQuery(), scope, false, QueryResult.Role.SUBQUERY, scope.prefix, null, path);
            roots.add(relation.getId());
            if (subquery.getProbe() != null) {
                collectSubqueryRoots(subquery.getProbe(), scope, roots);
            }
        } else if (expression instanceof BinarySyntax) {
            collectSubqueryRoots(((BinarySyntax) expression).getLeft(), scope, roots);
            collectSubqueryRoots(((BinarySyntax) expression).getRight(), scope, roots);
        } else if (expression instanceof com.panfan.hivelineage.v2.syntax.UnarySyntax) {
            collectSubqueryRoots(((com.panfan.hivelineage.v2.syntax.UnarySyntax) expression).getOperand(), scope, roots);
        } else if (expression instanceof com.panfan.hivelineage.v2.syntax.CallSyntax) {
            for (SyntaxExpression argument : ((com.panfan.hivelineage.v2.syntax.CallSyntax) expression).getArguments().getArguments()) {
                collectSubqueryRoots(argument, scope, roots);
            }
        } else if (expression instanceof com.panfan.hivelineage.v2.syntax.CastSyntax) {
            collectSubqueryRoots(((com.panfan.hivelineage.v2.syntax.CastSyntax) expression).getExpression(), scope, roots);
        } else if (expression instanceof com.panfan.hivelineage.v2.syntax.CaseSyntax) {
            com.panfan.hivelineage.v2.syntax.CaseSyntax cases = (com.panfan.hivelineage.v2.syntax.CaseSyntax) expression;
            if (cases.getOperand() != null) {
                collectSubqueryRoots(cases.getOperand(), scope, roots);
            }
            for (com.panfan.hivelineage.v2.syntax.CaseBranchSyntax branch : cases.getBranches()) {
                collectSubqueryRoots(branch.getWhenExpression(), scope, roots);
                collectSubqueryRoots(branch.getThenExpression(), scope, roots);
            }
            if (cases.getElseExpression() != null) {
                collectSubqueryRoots(cases.getElseExpression(), scope, roots);
            }
        }
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
            replacement = new Relations.Join(join.getId(), BindingWork.combined(left.getOutputSchema(), right.getOutputSchema(), true),
                join.getQueryBlockId(), join.getSourceOrder(), join.getSourceRef().orElse(null), join.getLeft(), join.getRight(),
                join.getJoinType(), join.getJoinForm(), join.getCondition().orElse(null), joinedMappings(left, right), join.getPredicates());
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
