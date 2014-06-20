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
package org.jboss.gwt.flux.sample.wmm.stores;

import java.util.HashSet;
import java.util.Set;

import org.jboss.gwt.flux.AbstractStore;
import org.jboss.gwt.flux.Action;
import org.jboss.gwt.flux.Agreement;
import org.jboss.gwt.flux.Dispatcher;
import org.jboss.gwt.flux.sample.wmm.actions.StartServerAction;
import org.jboss.gwt.flux.sample.wmm.actions.StopServerAction;

/**
 * @author Harald Pehl
 */
public class HostStore extends AbstractStore {

    public final Set<String> runningServers = new HashSet<>();

    public HostStore(final Dispatcher dispatcher) {

        dispatcher.register(HostStore.class, new Callback() {
            @Override
            public Agreement voteFor(final Action action) {
                Agreement agreement = Agreement.NONE;
                if (action instanceof StartServerAction) {
                    agreement = new Agreement(true);
                } else if (action instanceof StopServerAction) {
                    agreement = new Agreement(true, DeploymentStore.class);
                }
                return agreement;
            }

            @Override
            public void execute(final Action action, final Dispatcher.Channel channel) {
                if (action instanceof StartServerAction) {
                    runningServers.add((String) action.getPayload());
                } else if (action instanceof StopServerAction) {
                    //noinspection SuspiciousMethodCalls
                    runningServers.remove(action.getPayload());
                }
                channel.ack();
            }
        });
    }
}