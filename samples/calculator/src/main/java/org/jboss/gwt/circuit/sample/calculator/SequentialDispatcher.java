/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.gwt.circuit.sample.calculator;

import org.jboss.gwt.circuit.Action;
import org.jboss.gwt.circuit.Dispatcher;
import org.jboss.gwt.circuit.NoopChannel;
import org.jboss.gwt.circuit.StoreCallback;

import java.util.HashMap;
import java.util.Map;

public class SequentialDispatcher implements Dispatcher {

    private final Map<Class<?>, StoreCallback> callbacks;

    public SequentialDispatcher() {callbacks = new HashMap<>();}

    @Override
    public <S> void register(final Class<S> store, final StoreCallback callback) {
        callbacks.put(store, callback);
    }

    @Override
    public void dispatch(final Action action) {
        System.out.printf("~-~-~-~-~ Processing %s\n", action);

        for (StoreCallback callback : callbacks.values()) {
            if (callback.voteFor(action).isApproved()) {
                callback.complete(action, NoopChannel.INSTANCE);
                callback.signalChange(action);
            } else {
                System.out.printf("Ignoring unsupported %s\n", action);
            }
        }

        System.out.printf("~-~-~-~-~ Finished\n\n");
    }

    @Override
    public void addDiagnostics(final Diagnostics diagnostics) {
        throw new UnsupportedOperationException("Diagnostics are not supported for " + SequentialDispatcher.class);
    }

    @Override
    public void removeDiagnostics(Diagnostics diagnostics) {
        throw new UnsupportedOperationException("Diagnostics are not supported for " + SequentialDispatcher.class);
    }
}
