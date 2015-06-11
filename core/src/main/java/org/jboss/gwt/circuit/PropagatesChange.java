package org.jboss.gwt.circuit;

import com.google.web.bindery.event.shared.HandlerRegistration;

/**
 * Interface meant to be implemented by stores in order to participate in change events.
 */
public interface PropagatesChange {

    interface Handler {

        void onChange(Action action);
    }

    /**
     * Registers a {@link PropagatesChange.Handler} to be notified when the store was modified.
     */
    HandlerRegistration addChangeHandler(Handler handler);

    /**
     * Registers a {@link PropagatesChange.Handler} to be notified only when the store was
     * modified by the specified action type.
     */
    HandlerRegistration addChangeHandler(Class<? extends Action> actionType, Handler handler);

    /**
     * Registers a {@link PropagatesChange.Handler} to be notified only when the store was
     * modified by the specified action instance.
     */
    HandlerRegistration addChangeHandler(Action action, Handler handler);
}
