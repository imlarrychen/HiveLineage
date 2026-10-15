package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Each pure effect model has one state transition handler. */
final class SessionEffectHandlers {
    private static final Map<Class<?>, BiConsumer<SessionStateDelta, BoundEffect>> HANDLERS = handlers();

    private SessionEffectHandlers() { }

    static void apply(SessionStateDelta delta, BoundEffect effect) {
        BiConsumer<SessionStateDelta, BoundEffect> handler = HANDLERS.get(effect.getClass());
        if (handler == null) {
            delta.markUncertain(effect);
            return;
        }
        handler.accept(delta, effect);
    }

    private static Map<Class<?>, BiConsumer<SessionStateDelta, BoundEffect>> handlers() {
        Map<Class<?>, BiConsumer<SessionStateDelta, BoundEffect>> handlers = new LinkedHashMap<>();
        add(handlers, Effects.SetDatabase.class, SessionStateDelta::setDatabase);
        add(handlers, Effects.SetVariable.class, SessionStateDelta::setVariable);
        add(handlers, Effects.SetOption.class, SessionStateDelta::setOption);
        add(handlers, Effects.CreateObject.class, SessionStateDelta::create);
        add(handlers, Effects.AlterObject.class, SessionStateDelta::alter);
        add(handlers, Effects.DropObject.class, SessionStateDelta::drop);
        add(handlers, Effects.RenameObject.class, SessionStateDelta::rename);
        add(handlers, Effects.RegisterDefinition.class, SessionStateDelta::register);
        return Collections.unmodifiableMap(handlers);
    }

    private static <E extends BoundEffect> void add(Map<Class<?>, BiConsumer<SessionStateDelta, BoundEffect>> handlers,
            Class<E> type, BiConsumer<SessionStateDelta, E> handler) {
        handlers.put(type, (state, effect) -> handler.accept(state, type.cast(effect)));
    }
}
