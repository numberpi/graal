package org.graalvm.tools.lsp.server.request;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.graalvm.tools.lsp.api.ContextAwareExecutor;
import org.graalvm.tools.lsp.exceptions.DiagnosticsNotification;
import org.graalvm.tools.lsp.server.utils.CoverageEventNode;
import org.graalvm.tools.lsp.server.utils.SourceSectionReference;
import org.graalvm.tools.lsp.server.utils.SourcePredicateBuilder;
import org.graalvm.tools.lsp.server.utils.SourceUtils;
import org.graalvm.tools.lsp.server.utils.TextDocumentSurrogate;
import org.graalvm.tools.lsp.server.utils.TextDocumentSurrogateMap;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleException;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.ExecutionEventNodeFactory;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionEvent;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionListener;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter.SourcePredicate;
import com.oracle.truffle.api.instrumentation.StandardTags.StatementTag;
import com.oracle.truffle.api.instrumentation.TruffleInstrument.Env;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;

public class CoverageRequestHandler extends AbstractRequestHandler {

    private final SourceCodeEvaluator sourceCodeEvaluator;

    public CoverageRequestHandler(Env env, TextDocumentSurrogateMap surrogateMap, ContextAwareExecutor contextAwareExecutor, SourceCodeEvaluator sourceCodeEvaluator) {
        super(env, surrogateMap, contextAwareExecutor);
        this.sourceCodeEvaluator = sourceCodeEvaluator;
    }

    public Boolean runCoverageAnalysisWithEnteredContext(final URI uri) throws DiagnosticsNotification {
        final TextDocumentSurrogate surrogateOfOpenedFile = surrogateMap.get(uri);
        TextDocumentSurrogate surrogateOfTestFile = sourceCodeEvaluator.createSurrogateForTestFile(surrogateOfOpenedFile, null);
        final URI runScriptUri = surrogateOfTestFile.getUri();

        clearRelatedCoverageData(runScriptUri);

        try {
            final CallTarget callTarget = sourceCodeEvaluator.parse(surrogateOfTestFile);
            LanguageInfo languageInfo = surrogateOfTestFile.getLanguageInfo();
            SourcePredicate predicate = SourcePredicateBuilder.newBuilder().language(languageInfo).excludeInternal(env.getOptions()).build();
            SourceSectionFilter eventFilter = SourceSectionFilter.newBuilder().sourceIs(predicate).build();
            EventBinding<ExecutionEventNodeFactory> eventFactoryBinding = env.getInstrumenter().attachExecutionEventFactory(
                            eventFilter,
                            new ExecutionEventNodeFactory() {
                                private final long creatorThreadId = Thread.currentThread().getId();

                                public ExecutionEventNode create(final EventContext eventContext) {
                                    final SourceSection section = eventContext.getInstrumentedSourceSection();
                                    if (section != null && section.isAvailable()) {
                                        final Node instrumentedNode = eventContext.getInstrumentedNode();
                                        Function<URI, TextDocumentSurrogate> func = (sourceUri) -> {
                                            return surrogateMap.getOrCreateSurrogate(sourceUri, () -> instrumentedNode.getRootNode().getLanguageInfo());
                                        };

                                        return new CoverageEventNode(section, instrumentedNode, runScriptUri, func, creatorThreadId);
                                    } else {
                                        return new ExecutionEventNode() {
                                        };
                                    }
                                }
                            });
            try {
                callTarget.call();
            } finally {
                eventFactoryBinding.dispose();
            }

            surrogateOfOpenedFile.setCoverageAnalysisDone(true);

            return Boolean.TRUE;
        } catch (DiagnosticsNotification e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof TruffleException) {
                Node location = ((TruffleException) e).getLocation();
                URI uriOfErronousSource = null;
                if (location != null) {
                    SourceSection sourceSection = location.getEncapsulatingSourceSection();
                    if (sourceSection != null) {
                        uriOfErronousSource = sourceSection.getSource().getURI();
                    }
                }

                if (uriOfErronousSource == null) {
                    uriOfErronousSource = uri;
                }
                throw DiagnosticsNotification.create(uriOfErronousSource,
                                new Diagnostic(SourceUtils.getRangeFrom((TruffleException) e), e.getMessage(), DiagnosticSeverity.Error, "Coverage analysis"));
            }

            throw e;
        }
    }

    /**
     * Clears all coverage data from previous runs, which was collected by running the runScriptUri
     * resource. This avoids clearing coverage data collected by other script runs.
     *
     * Also clears all coverage data for the runScriptUri resource itself.
     *
     * @param runScriptUri URI of the script to kick-off the coverage analysis
     */
    private void clearRelatedCoverageData(final URI runScriptUri) {
        TextDocumentSurrogate surrogateOfRunScript = surrogateMap.get(runScriptUri);
        assert surrogateOfRunScript != null;
        surrogateOfRunScript.clearCoverage();
        surrogateMap.getSurrogates().stream().forEach(surrogate -> surrogate.clearCoverage(runScriptUri));
    }

    public void showCoverageWithEnteredContext(URI uri) throws DiagnosticsNotification {
        final TextDocumentSurrogate surrogate = surrogateMap.get(uri);
        assert surrogate != null;
        if (surrogate.getSourceWrapper() != null && surrogate.getSourceWrapper().isParsingSuccessful()) {
            // @formatter:off
            SourceSectionFilter filter = SourceSectionFilter.newBuilder()
                            .sourceIs(surrogate.getSourceWrapper().getSource())
                            .tagIs(StatementTag.class)
                            .build();
            Set<SourceSection> duplicateFilter = new HashSet<>();
            Map<URI, PublishDiagnosticsParams> mapDiagnostics = new HashMap<>();
            env.getInstrumenter().attachLoadSourceSectionListener(filter, new LoadSourceSectionListener() {

                public void onLoad(LoadSourceSectionEvent event) {
                    SourceSection section = event.getSourceSection();
                    if (!surrogate.isLocationCovered(SourceSectionReference.from(section)) && !duplicateFilter.contains(section)) {
                        duplicateFilter.add(section);
                        Diagnostic diag = new Diagnostic(SourceUtils.sourceSectionToRange(section),
                                                         "Not covered",
                                                         DiagnosticSeverity.Warning,
                                                         "Coverage Analysis");
                        PublishDiagnosticsParams params = mapDiagnostics.computeIfAbsent(uri, _uri -> new PublishDiagnosticsParams(_uri.toString(), new ArrayList<>()));
                        params.getDiagnostics().add(diag);
                    }
                }
            }, true).dispose();
            throw new DiagnosticsNotification(mapDiagnostics.values());
            // @formatter:on
        } else {
            throw DiagnosticsNotification.create(uri,
                            new Diagnostic(new Range(new Position(), new Position()), "No coverage information available", DiagnosticSeverity.Error, "Coverage Analysis"));
        }
    }
}