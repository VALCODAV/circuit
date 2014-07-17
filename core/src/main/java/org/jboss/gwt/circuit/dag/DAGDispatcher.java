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
package org.jboss.gwt.circuit.dag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.jboss.gwt.circuit.Action;
import org.jboss.gwt.circuit.Agreement;
import org.jboss.gwt.circuit.Dispatcher;
import org.jboss.gwt.circuit.StoreCallback;
import org.jgrapht.DirectedGraph;
import org.jgrapht.alg.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;

/**
 * A dispatcher implementation with global locking and dependency resolution between stores based on directed acyclic
 * graphs (DAG).
 *
 * @see <a href="http://en.wikipedia.org/wiki/Directed_acyclic_graph">http://en.wikipedia.org/wiki/Directed_acyclic_graph</a>
 * @see <a href="http://en.wikipedia.org/wiki/Topological_ordering">http://en.wikipedia.org/wiki/Topological_ordering</a>
 */
public class DAGDispatcher implements Dispatcher {

    public interface Diagnostics extends Dispatcher.Diagnostics {

        void onDispatch(Action a);

        void onLock();

        void onExecute(Class<?> s, Action a);

        void onAck(Class<?> s, Action a);

        void onNack(Class<?> s, Action a, final Throwable t);

        void onUnlock();
    }


    public final static int BOUNDS_SIZE = 50;

    private boolean locked;
    private final Queue<Action> queue;
    private final Map<Class<?>, StoreCallback> callbacks;
    private final CompoundDiagnostics diagnostics;

    public DAGDispatcher() {
        locked = false;
        queue = new BoundedQueue<>(BOUNDS_SIZE);
        callbacks = new HashMap<>();
        diagnostics = new CompoundDiagnostics();
    }

    @Override
    public <S> void register(final Class<S> store, final StoreCallback callback) {
        assert callbacks.get(store) == null : "Store " + store.getName() + " already registered!";
        callbacks.put(store, callback);
    }

    @Override
    public void dispatch(final Action action) {
        diagnostics.onDispatch(action);

        if (!locked) {
            dispatchInternal(action);

        } else {
            boolean accepted = queue.offer(action);
            if (!accepted) {
                System.out.println("WARN: Dispatcher is dropping action " + action.getClass()
                        .getName() + ", due to exceeded buffer");
            }
        }
    }

    private void dispatchInternal(final Action action) {
        // lock globally
        lock();

        // collect approvals
        Map<Class<?>, Agreement> approvals = prepare(action);

        // complete callbacks
        if (approvals.isEmpty()) {
            unlock();
        } else {
            complete(action, approvals);
        }
    }

    private void lock() {
        diagnostics.onLock();
        locked = true;
    }

    private void unlock() {
        diagnostics.onUnlock();
        locked = false;
    }

    private Map<Class<?>, Agreement> prepare(final Action action) {
        Map<Class<?>, Agreement> approvals = new HashMap<>();
        for (Map.Entry<Class<?>, StoreCallback> entry : callbacks.entrySet()) {
            Class<?> store = entry.getKey();
            StoreCallback callback = entry.getValue();

            Agreement agreement = callback.voteFor(action);
            if (agreement.isApproved()) {
                approvals.put(store, agreement);
            }
        }
        return approvals;
    }

    private void complete(final Action action, final Map<Class<?>, Agreement> approvals) {
        DirectedGraph<Class<?>, DefaultEdge> dag = createDag(approvals);
        // TODO Cache topological order
        TopologicalOrderIterator<Class<?>, DefaultEdge> iterator = new TopologicalOrderIterator<>(dag);

        executeInOrder(action, iterator, new ArrayList<StoreCallback>());
    }

    private DirectedGraph<Class<?>, DefaultEdge> createDag(final Map<Class<?>, Agreement> approvals) {
        DirectedGraph<Class<?>, DefaultEdge> dag = new DefaultDirectedGraph<>(new EdgeFactoryImpl());

        // Add vertices (stores)
        for (Map.Entry<Class<?>, Agreement> entry : approvals.entrySet()) {
            Class<?> store = entry.getKey();
            Agreement agreement = entry.getValue();
            dag.addVertex(store);
            for (Class<?> depStore : agreement.getDependencies()) {
                dag.addVertex(depStore);
            }
        }

        // Add edges (dependencies from one store to other stores)
        for (Map.Entry<Class<?>, Agreement> entry : approvals.entrySet()) {
            Class<?> store = entry.getKey();
            Agreement agreement = entry.getValue();
            for (Class<?> depStore : agreement.getDependencies()) {
                dag.addEdge(depStore, store);
            }
        }

        // cycle detection
        CycleDetector<Class<?>, DefaultEdge> cycleDetection = new CycleDetector<>(dag);
        Set<Class<?>> cycles = cycleDetection.findCycles();
        if (cycles.size() > 0) {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (Class<?> store : cycles) {
                sb.append(store.getName());
                if (i < cycles.size()) { sb.append(" > "); }
                i++;
            }

            throw new CycleDetected(sb.toString());
        }

        return dag;
    }

    private void executeInOrder(final Action action, final TopologicalOrderIterator<Class<?>, DefaultEdge> iterator,
            final List<StoreCallback> acknowledgedCallbacks) {
        if (!iterator.hasNext()) {
            signalChange(acknowledgedCallbacks, action);
            unlock();
            if (!queue.isEmpty()) {
                dispatchInternal(queue.poll());
            }
            return;
        }

        final Class<?> store = iterator.next();
        final StoreCallback callback = callbacks.get(store);
        diagnostics.onExecute(store, action);
        callback.complete(action, new Channel() {
            @Override
            public void ack() {
                diagnostics.onAck(store, action);
                acknowledgedCallbacks.add(callback);
                proceed();
            }

            @Override
            public void nack(final Throwable t) {
                diagnostics.onNack(store, action, t);
                proceed();
            }

            private void proceed() {
                executeInOrder(action, iterator, acknowledgedCallbacks);
            }
        });
    }

    private void signalChange(List<StoreCallback> acknowledgedCallbacks, Action action) {
        for (StoreCallback callback : acknowledgedCallbacks) {
            callback.signalChange(action);
        }
    }

    @Override
    public void addDiagnostics(final Dispatcher.Diagnostics d) {
        this.diagnostics.add(d);
    }

    @Override
    public void removeDiagnostics(Dispatcher.Diagnostics d) {
        this.diagnostics.remove(d);
    }
}

