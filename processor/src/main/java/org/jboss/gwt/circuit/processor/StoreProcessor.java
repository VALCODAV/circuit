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

import static javax.tools.Diagnostic.Kind.ERROR;
import static javax.tools.Diagnostic.Kind.NOTE;
import static org.jboss.gwt.circuit.processor.GenerationUtil.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.jboss.gwt.circuit.Dispatcher;
import org.jboss.gwt.circuit.meta.*;
import org.jboss.gwt.circuit.meta.Process;
import org.jgrapht.DirectedGraph;
import org.jgrapht.alg.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

@SupportedSourceVersion(SourceVersion.RELEASE_7)
@SupportedAnnotationTypes("org.jboss.gwt.circuit.meta.Store")
public class StoreProcessor extends AbstractErrorAbsorbingProcessor {

    static final String GRAPH_VIZ_OUTPUT = "dependencies.gv";

    private final Map<String, GraphVizInfo> graphVizInfos;
    private final Map<String, Multimap<String, String>> dagValidation;

    public StoreProcessor() {
        graphVizInfos = new HashMap<>();
        dagValidation = new HashMap<>();
    }

    @Override
    protected boolean processWithExceptions(final Set<? extends TypeElement> annotations,
            final RoundEnvironment roundEnv)
            throws Exception {

        if (roundEnv.errorRaised()) {
            return false;
        }

        final Messager messager = processingEnv.getMessager();
        if (!roundEnv.processingOver()) {
            final Types typeUtils = processingEnv.getTypeUtils();
            final Elements elementUtils = processingEnv.getElementUtils();
            final Map<String, String> options = processingEnv.getOptions();

            // store annotations
            for (Element e : roundEnv.getElementsAnnotatedWith(Store.class)) {
                TypeElement storeElement = (TypeElement) e;
                PackageElement packageElement = (PackageElement) storeElement.getEnclosingElement();

                final String packageName = packageElement.getQualifiedName().toString();
                final String storeDelegate = storeElement.getSimpleName().toString();
                final String storeClassName = GenerationUtil.storeImplementation(storeDelegate);
                messager.printMessage(NOTE, "Discovered annotated store [" + storeElement.getQualifiedName() + "]");

                List<ExecutableElement> receiveMethods = new ArrayList<>();
                if (findValidReceiveMethods(messager, typeUtils, elementUtils, storeElement, receiveMethods)) {
                    Collection<ReceiveInfo> receiveInfos = getReceiveInfos(messager, typeUtils, storeElement,
                            receiveMethods);
                    try {
                        messager.printMessage(NOTE, "Generating code for [" + storeClassName + "]");
                        StoreGenerator generator = new StoreGenerator();
                        final StringBuffer code = generator.generate(packageName, storeClassName, storeDelegate,receiveInfos);
                        writeCode(packageName, storeClassName, code);

                        messager.printMessage(NOTE,
                                "Successfully generated store implementation [" + storeClassName + "]");
                    } catch (GenerationException ge) {
                        final String msg = ge.getMessage();
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, storeElement);
                    }
                } else {
                    // no valid receive methods - get out!
                    break;
                }
            }

        } else {
            // After all files were generated write GraphViz and validate dependencies
            String graphVizFile = writeGraphViz(messager);
            validateDAG(messager, graphVizFile);
        }
        return true;
    }

    private boolean findValidReceiveMethods(final Messager messager, final Types typeUtils, final Elements elementUtils,
            final TypeElement storeElement, List<ExecutableElement> receiveMethods) {

        boolean valid = true;
        StringBuilder errorMessage = new StringBuilder();
        NoType voidType = typeUtils.getNoType(TypeKind.VOID);
        List<ExecutableElement> allReceiveMethods = getAnnotatedMethods(storeElement, processingEnv,
                Process.class.getName(), voidType, ANY_PARAMS, errorMessage);
        if (allReceiveMethods.isEmpty()) {
            messager.printMessage(ERROR, String.format(
                    "No receive methods found in [%s]. Please use @%s to mark one or several methods as receive methods.",
                    storeElement.getQualifiedName(), Process.class.getName()));
            valid = false;
        }
        for (ExecutableElement receiveMethod : allReceiveMethods) {
            receiveMethods.add(receiveMethod);
        }
        return valid;
    }

    private Collection<ReceiveInfo> getReceiveInfos(final Messager messager, final Types typeUtils,
            final TypeElement storeElement, final List<ExecutableElement> receiveMethods) throws GenerationException {

        final List<ReceiveInfo> receiveInfos = new LinkedList<>();
        final String storeDelegate = storeElement.getSimpleName().toString();
        for (ExecutableElement methodElement : receiveMethods) {

            String actionType = Void.class.getCanonicalName();
            Collection<String> dependencies = Collections.emptySet();
            for (AnnotationMirror am : methodElement.getAnnotationMirrors()) {
                if (org.jboss.gwt.circuit.meta.Process.class.getName().equals(am.getAnnotationType().toString())) {
                    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : am
                            .getElementValues().entrySet()) {
                        if ("dependencies".equals(entry.getKey().getSimpleName().toString())) {
                            dependencies = GenerationUtil.extractValue(entry.getValue());
                        }
                        else if ("actionType".equals(entry.getKey().getSimpleName().toString())) {
                            actionType = (String)((Set)GenerationUtil.extractValue(entry.getValue())).iterator().next();
                        }
                    }
                }
            }

            if(methodElement.getParameters().size()==2) {

                // first parameter is action actionType, the second one a channel
                VariableElement payloadParameter = methodElement.getParameters().get(0);
                TypeElement payloadParameterType = (TypeElement) typeUtils.asElement(payloadParameter.asType());
                receiveInfos.add(
                        new ReceiveInfo(methodElement.getSimpleName().toString(),
                                actionType,
                                payloadParameterType.getQualifiedName().toString()
                        )
                );
            }
            else if(methodElement.getParameters().size()==1)
            {
                // if a single param is used it need to be a channel
                VariableElement param = methodElement.getParameters().get(0);
                TypeElement paramType = (TypeElement) typeUtils.asElement(param.asType());
                if(!paramType.getQualifiedName().toString().equals(Dispatcher.Channel.class.getCanonicalName()))
                {
                    String error = String.format(
                            "Illegal type for parameter '%s' on method '%s' in class '%s'. Expected type "+Dispatcher.Channel.class.getCanonicalName(),
                            param.getSimpleName(), methodElement.getSimpleName(), storeElement.getSimpleName());
                    messager.printMessage(Diagnostic.Kind.ERROR, error);
                    continue;
                }

                receiveInfos.add(
                        new ReceiveInfo(methodElement.getSimpleName().toString(),
                                actionType
                        )
                );
            }
            else
            {
                // anything beyond two parameters on receive methods is considered an error
                String error = String.format(
                        "Illegal number of argument on method '%s' in class '%s'",
                        methodElement.getSimpleName(), storeElement.getSimpleName());
                messager.printMessage(Diagnostic.Kind.ERROR, error);
                continue;
            }

            // --------------------------

            ReceiveInfo receiveInfo = receiveInfos.get(receiveInfos.size() - 1);
            for(String store : dependencies)   {
                // IMPORTANT: The actual dependency is the adapter class!
                final String storeAdapter = GenerationUtil.storeImplementation(store);
                receiveInfo.addDependency(storeAdapter+ ".class");
            }

            // --------------------------

            // record dependencies in a different data structures to generate GraphViz...

            GraphVizInfo graphVizInfo = graphVizInfos.get(actionType);
            if (graphVizInfo == null) {
                graphVizInfo = new GraphVizInfo(actionType);
                graphVizInfos.put(actionType, graphVizInfo);
            }
            graphVizInfo.addStore(storeDelegate);
            List<String> simpleDependencies = new LinkedList<>();
            for (String dependency : dependencies) {
                String simpleDependency = dependency.substring(dependency.lastIndexOf('.') + 1);
                simpleDependencies.add(simpleDependency);
                graphVizInfo.addStore(simpleDependency);
                graphVizInfo.addDependency(storeDelegate, simpleDependency);
            }

            // --------------------------
            // ...and verify DAG
            Multimap<String, String> dag = dagValidation.get(actionType);
            if (dag == null) {
                dag = HashMultimap.create();
                dagValidation.put(actionType, dag);
            }
            dag.putAll(storeDelegate, simpleDependencies);
        }

        return receiveInfos;
    }

    private String writeGraphViz(final Messager messager) throws GenerationException, IOException {
        GraphVizGenerator generator = new GraphVizGenerator();
        StringBuffer code = generator.generate(graphVizInfos.values());
        messager.printMessage(NOTE,
                "Generating GraphViz file to visualize store dependencies [" + GRAPH_VIZ_OUTPUT + "]");
        FileObject fo = processingEnv.getFiler()
                .createResource(StandardLocation.SOURCE_OUTPUT, "", GRAPH_VIZ_OUTPUT);
        Writer w = fo.openWriter();
        BufferedWriter bw = new BufferedWriter(w);
        bw.append(code);
        bw.close();
        w.close();
        messager.printMessage(NOTE, "Successfully generated GraphViz file [" + GRAPH_VIZ_OUTPUT + "]");
        return fo.getName();
    }

    private void validateDAG(final Messager messager, final String graphVizFile) throws GenerationException {
        boolean cyclesFound = false;
        for (Map.Entry<String, Multimap<String, String>> entry : dagValidation.entrySet()) {
            String payload = entry.getKey();
            Multimap<String, String> dependencies = entry.getValue();
            messager.printMessage(NOTE, "Check cyclic dependencies for action [" + payload + "]");
            DirectedGraph<String, DefaultEdge> dg = new DefaultDirectedGraph<>(DefaultEdge.class);

            // vertices
            for (String store : dependencies.keySet()) {
                dg.addVertex(store);
            }
            for (String store : dependencies.values()) {
                dg.addVertex(store);
            }

            // edges
            for (String store : dependencies.keySet()) {
                Collection<String> storeDependencies = dependencies.get(store);
                for (String storeDependency : storeDependencies) {
                    dg.addEdge(store, storeDependency);
                }
            }

            // cycles?
            CycleDetector<String, DefaultEdge> detector = new CycleDetector<>(dg);
            List<String> cycles = new LinkedList<>(detector.findCycles());
            if (!cycles.isEmpty()) {
                cyclesFound = true;
                StringBuilder cycleInfo = new StringBuilder();
                for (String cycle : cycles) {
                    cycleInfo.append(cycle).append(" -> ");
                }
                cycleInfo.append(cycles.get(0));
                messager.printMessage(ERROR,
                        "Cyclic dependencies detected for action [" + payload + "]: " + cycleInfo);
                messager.printMessage(ERROR, "Please review [" + graphVizFile + "] for more details.");
            }
            if (!cyclesFound) {
                messager.printMessage(NOTE, "No cyclic dependencies found for action [" + payload + "]");
            }
        }
    }
}
