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
package org.jboss.gwt.circuit.processor;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

class StoreGenerator extends AbstractGenerator {

    StringBuffer generate(final String packageName, final String storeClassName, String storeDelegate,
            final boolean changeSupport, Collection<ProcessInfo> processInfos)
            throws GenerationException {

        Map<String, Object> context = new HashMap<>();
        context.put("packageName", packageName);
        context.put("storeClassName", storeClassName);
        context.put("storeDelegate", storeDelegate);
        context.put("changeSupport", changeSupport);
        context.put("processInfos", processInfos);
        return generate(context, "Store.ftl");
    }
}
