package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.syntax.CommandStatementSyntax;
import com.panfan.hivelineage.v2.syntax.CommandSyntax;
import com.panfan.hivelineage.v2.syntax.SetCommandSyntax;
import com.panfan.hivelineage.v2.syntax.UseCommandSyntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Proposes session changes without reading or mutating the catalog. */
final class SessionCommandEffectBinder {
    private static final Map<String, Effects.Namespace> NAMESPACES = namespaces();

    EffectBindingResult bind(CommandStatementSyntax statement, BindingContext context) {
        CommandSyntax command = statement.getCommand();
        NodeId id = new NodeId(statement.getNodeId().getValue() + "/session-effect");
        Effects.Precondition always = new Effects.Precondition(Effects.Precondition.Kind.ALWAYS, null);
        List<BoundEffect> effects = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (command instanceof UseCommandSyntax) {
            QualifiedName database = SemanticNames.name(((UseCommandSyntax) command).getDatabase());
            if (database.getDisplayName().equalsIgnoreCase(context.getContract().getFallbackVariableValue())) {
                diagnostics.add(new Diagnostic(AnalysisStage.BINDING, "INVALID_USE_DATABASE",
                        "A fallback variable cannot set the current database", statement.getSourceRef()));
            } else {
                effects.add(new Effects.SetDatabase(id, Effects.Certainty.CERTAIN, always, database));
            }
        } else if (command instanceof SetCommandSyntax && ((SetCommandSyntax) command).getValue() != null) {
            SetCommandSyntax set = (SetCommandSyntax) command;
            Effects.Namespace namespace = NAMESPACES.getOrDefault(set.getNamespace().toLowerCase(Locale.ROOT), Effects.Namespace.HIVEVAR);
            effects.add(new Effects.SetVariable(id, Effects.Certainty.CERTAIN, always,
                    new Effects.SessionKey(namespace, set.getKey()), set.getValue()));
        }
        return new EffectBindingResult(effects, new DiagnosticBag(diagnostics));
    }

    private static Map<String, Effects.Namespace> namespaces() {
        Map<String, Effects.Namespace> namespaces = new LinkedHashMap<>();
        namespaces.put("", Effects.Namespace.CONFIGURATION);
        namespaces.put("unqualified", Effects.Namespace.CONFIGURATION);
        namespaces.put("hiveconf", Effects.Namespace.HIVECONF);
        namespaces.put("system", Effects.Namespace.SYSTEM_PROPERTY);
        namespaces.put("env", Effects.Namespace.ENVIRONMENT);
        return Collections.unmodifiableMap(namespaces);
    }
}
