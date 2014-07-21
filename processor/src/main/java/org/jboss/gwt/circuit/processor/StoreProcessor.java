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
import static org.jboss.gwt.circuit.processor.GenerationUtil.ANY_PARAMS;

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
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
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
import org.jboss.gwt.circuit.ChangeSupport;
import org.jboss.gwt.circuit.Dispatcher;
import org.jboss.gwt.circuit.meta.Process;
import org.jboss.gwt.circuit.meta.Store;
import org.jgrapht.DirectedGraph;
import org.jgrapht.alg.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

@SupportedSourceVersion(SourceVersion.RELEASE_7)
@SupportedAnnotationTypes("org.jboss.gwt.circuit.meta.Store")
public class StoreProcessor extends AbstractErrorAbsorbingProcessor {

    // TODO (hpehl) Remove helper class once this processor is no longer referenced by hal/core:SPIProcessor
    public static class StoreGenerationMetadata {

        public final String packageName;
        public final String storeClassName;
        public final String storeDelegate;
        public final boolean changeSupport;
        public final Collection<ProcessInfo> processInfos;

        public StoreGenerationMetadata(final String packageName, final String storeClassName,
                final String storeDelegate, final boolean changeSupport, final Collection<ProcessInfo> processInfos) {
            this.packageName = packageName;
            this.storeClassName = storeClassName;
            this.storeDelegate = storeDelegate;
            this.changeSupport = changeSupport;
            this.processInfos = processInfos;
        }
    }


    static final String GRAPH_VIZ_OUTPUT = "dependencies.gv";

    private final Map<String, GraphVizInfo> graphVizInfos;
    private final Map<String, Multimap<String, String>> dagValidation;
    private final List<StoreGenerationMetadata> metadata;

    public StoreProcessor() {
        graphVizInfos = new HashMap<>();
        dagValidation = new HashMap<>();
        metadata = new ArrayList<>();
    }

    @Override
    protected boolean processWithExceptions(final Set<? extends TypeElement> annotations,
            final RoundEnvironment roundEnv)
            throws Exception {

        if (roundEnv.errorRaised()) {
            return false;
        }
        if (!roundEnv.processingOver()) {
            processStores(processingEnv, roundEnv);
        } else {
            writeStores(processingEnv);
            storePostProcessing(processingEnv);
        }
        return true;
    }

    // TODO (hpehl) Remove public visibility once this processor is no longer referenced by hal/core:SPIProcessor
    public void processStores(final ProcessingEnvironment pe, final RoundEnvironment roundEnv) throws Exception {

        final Messager messager = pe.getMessager();
        final Types typeUtils = pe.getTypeUtils();
        final Elements elementUtils = pe.getElementUtils();

        // store annotations
        for (Element e : roundEnv.getElementsAnnotatedWith(Store.class)) {
            TypeElement storeElement = (TypeElement) e;
            PackageElement packageElement = (PackageElement) storeElement.getEnclosingElement();

            final String packageName = packageElement.getQualifiedName().toString();
            final String storeDelegate = storeElement.getSimpleName().toString();
            final boolean changeSupport = typeUtils.isAssignable(storeElement.asType(),
                    elementUtils.getTypeElement(ChangeSupport.class.getName()).asType());
            final String storeClassName = GenerationUtil.storeImplementation(storeDelegate);
            messager.printMessage(NOTE,
                    String.format("Discovered annotated store [%s]", storeElement.getQualifiedName()));

            List<ExecutableElement> processMethods = new ArrayList<>();
            if (findValidProcessMethods(pe, messager, typeUtils, storeElement, processMethods)) {
                Collection<ProcessInfo> processInfos = getProcessInfos(messager, typeUtils, storeElement,
                        processMethods);

                metadata.add(new StoreGenerationMetadata(packageName, storeClassName, storeDelegate, changeSupport,
                        processInfos));
            } else {
                // no valid process methods!
                messager.printMessage(ERROR,
                        String.format("%s does not contain suitable methods annotated with %s.",
                                storeElement.getQualifiedName(), Process.class.getName()));
                break;
            }
        }
    }

    public void writeStores(final ProcessingEnvironment pe) throws Exception {
        final Messager messager = pe.getMessager();
        for (StoreGenerationMetadata md : metadata) {
            try {
                messager.printMessage(NOTE, String.format("Generating code for [%s]", md.storeClassName));
                StoreGenerator generator = new StoreGenerator();
                final StringBuffer code = generator.generate(md.packageName, md.storeClassName, md.storeDelegate,
                        md.changeSupport, md.processInfos);
                writeCode(pe, md.packageName, md.storeClassName, code);

                messager.printMessage(NOTE,
                        String.format("Successfully generated store implementation [%s]", md.storeClassName));
            } catch (GenerationException ge) {
                final String msg = ge.getMessage();
                messager.printMessage(Diagnostic.Kind.ERROR, msg/* , storeElement*/);
            }
        }
    }

    // TODO (hpehl) Remove public visibility once this processor is no longer referenced by hal/core:SPIProcessor
    public void storePostProcessing(ProcessingEnvironment pe) throws Exception {
        String graphVizFile = writeGraphViz(pe);
        validateDAG(pe, graphVizFile);
    }

    private boolean findValidProcessMethods(final ProcessingEnvironment pe, final Messager messager,
            final Types typeUtils, final TypeElement storeElement, List<ExecutableElement> processMethods) {

        boolean valid = true;
        StringBuilder errorMessage = new StringBuilder();
        NoType voidType = typeUtils.getNoType(TypeKind.VOID);
        List<ExecutableElement> allProcessMethods = GenerationUtil.getAnnotatedMethods(storeElement, pe,
                Process.class.getName(), voidType, ANY_PARAMS, errorMessage);
        if (allProcessMethods.isEmpty()) {
            messager.printMessage(ERROR, String.format(
                    "No process methods found in [%s]. Please use @%s to mark one or several methods as process methods.",
                    storeElement.getQualifiedName(), Process.class.getName()));
            valid = false;
        }
        for (ExecutableElement processMethod : allProcessMethods) {
            processMethods.add(processMethod);
        }
        return valid;
    }

    private Collection<ProcessInfo> getProcessInfos(final Messager messager, final Types typeUtils,
            final TypeElement storeElement, final List<ExecutableElement> processMethods) throws GenerationException {

        final List<ProcessInfo> processInfos = new LinkedList<>();
        final String storeDelegate = storeElement.getSimpleName().toString();
        for (ExecutableElement methodElement : processMethods) {

            String actionType = Void.class.getCanonicalName();
            Collection<String> dependencies = Collections.emptySet();
            for (AnnotationMirror am : methodElement.getAnnotationMirrors()) {
                if (org.jboss.gwt.circuit.meta.Process.class.getName().equals(am.getAnnotationType().toString())) {
                    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : am
                            .getElementValues().entrySet()) {
                        if ("dependencies".equals(entry.getKey().getSimpleName().toString())) {
                            dependencies = GenerationUtil.extractValue(entry.getValue());
                        } else if ("actionType".equals(entry.getKey().getSimpleName().toString())) {
                            actionType = (String) ((Set) GenerationUtil.extractValue(entry.getValue())).iterator()
                                    .next();
                        }
                    }
                }
            }

            if (methodElement.getParameters().size() == 2) {
                // first parameter is action actionType, the second one the back channel
                VariableElement payloadParameter = methodElement.getParameters().get(0);
                TypeElement payloadParameterType = (TypeElement) typeUtils.asElement(payloadParameter.asType());
                processInfos.add(
                        new ProcessInfo(methodElement.getSimpleName().toString(), actionType,
                                payloadParameterType.getQualifiedName().toString()));

            } else if (methodElement.getParameters().size() == 1) {
                // if a single param is used it need to be the back channel
                VariableElement param = methodElement.getParameters().get(0);
                TypeElement paramType = (TypeElement) typeUtils.asElement(param.asType());
                if (!paramType.getQualifiedName().toString().equals(Dispatcher.Channel.class.getCanonicalName())) {
                    String error = String.format(
                            "Illegal type for parameter '%s' on method '%s' in class '%s'. Expected type '%s'",
                            param.getSimpleName(), methodElement.getSimpleName(), storeElement.getSimpleName(),
                            Dispatcher.Channel.class.getCanonicalName());
                    messager.printMessage(Diagnostic.Kind.ERROR, error);
                    continue;
                }
                processInfos.add(new ProcessInfo(methodElement.getSimpleName().toString(), actionType));

            } else {
                // anything beyond two parameters on receive methods is considered an error
                String error = String.format(
                        "Illegal number of argument on method '%s' in class '%s'",
                        methodElement.getSimpleName(), storeElement.getSimpleName());
                messager.printMessage(Diagnostic.Kind.ERROR, error);
                continue;
            }

            // --------------------------
            // collect infos for the code generation
            ProcessInfo processInfo = processInfos.get(processInfos.size() - 1);
            for (String store : dependencies) {
                // IMPORTANT: The actual dependency is the store adaptee!
                processInfo.addDependency(store + ".class");
            }

            // --------------------------
            // record dependencies in a different data structures to generate GraphViz...
            GraphVizInfo graphVizInfo = graphVizInfos.get(actionType);
            if (graphVizInfo == null) {
                String shortActionType = actionType.substring(actionType.lastIndexOf('.') + 1);
                graphVizInfo = new GraphVizInfo(shortActionType);
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

        return processInfos;
    }

    private String writeGraphViz(final ProcessingEnvironment pe) throws GenerationException, IOException {
        final Messager messager = pe.getMessager();
        GraphVizGenerator generator = new GraphVizGenerator();
        StringBuffer code = generator.generate(graphVizInfos.values());
        messager.printMessage(NOTE,
                "Generating GraphViz file to visualize store dependencies [" + GRAPH_VIZ_OUTPUT + "]");
        FileObject fo = pe.getFiler()
                .createResource(StandardLocation.SOURCE_OUTPUT, "", GRAPH_VIZ_OUTPUT);
        Writer w = fo.openWriter();
        BufferedWriter bw = new BufferedWriter(w);
        bw.append(code);
        bw.close();
        w.close();
        messager.printMessage(NOTE, "Successfully generated GraphViz file [" + GRAPH_VIZ_OUTPUT + "]");
        return fo.getName();
    }

    private void validateDAG(final ProcessingEnvironment pe, final String graphVizFile) throws GenerationException {
        boolean cyclesFound = false;
        final Messager messager = pe.getMessager();
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
