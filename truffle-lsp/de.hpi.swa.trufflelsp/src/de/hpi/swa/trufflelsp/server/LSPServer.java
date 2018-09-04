package de.hpi.swa.trufflelsp.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import com.google.gson.JsonPrimitive;

import de.hpi.swa.trufflelsp.TruffleAdapter;
import de.hpi.swa.trufflelsp.exceptions.UnknownLanguageException;

public class LSPServer implements LanguageServer, LanguageClientAware, TextDocumentService, WorkspaceService, DiagnosticsPublisher {
    private static final String SHOW_COVERAGE = "show_coverage";
    private static final String ANALYSE_COVERAGE = "analyse_coverage";
    private static final TextDocumentSyncKind TEXT_DOCUMENT_SYNC_KIND = TextDocumentSyncKind.Incremental;
    private final TruffleAdapter truffleAdapter;
    private final PrintWriter err;
    private final PrintWriter info;
// private int shutdown = 1;
    private LanguageClient client;
    private Map<URI, String> openedFileUri2LangId = new HashMap<>();
    private String trace_server = "off";
    private Map<URI, PublishDiagnosticsParams> diagnostics = new HashMap<>();
    private ExecutorService executor;
    private ServerSocket serverSocket;

    private LSPServer(TruffleAdapter adapter, PrintWriter info, PrintWriter err) {
        this.truffleAdapter = adapter;
        this.info = info;
        this.err = err;
    }

    public static LSPServer create(TruffleAdapter adapter, PrintWriter info, PrintWriter err) {
        LSPServer server = new LSPServer(adapter, info, err);
        adapter.setDiagnosticsPublisher(server);
        return server;
    }

    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        List<String> signatureTriggerChars = Arrays.asList("(");
        final SignatureHelpOptions signatureHelpOptions = new SignatureHelpOptions(signatureTriggerChars);

        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TEXT_DOCUMENT_SYNC_KIND);
        capabilities.setDocumentSymbolProvider(true);
        capabilities.setWorkspaceSymbolProvider(true);
        capabilities.setDefinitionProvider(true);
        capabilities.setDocumentHighlightProvider(false);
        capabilities.setCodeLensProvider(new CodeLensOptions(false));
        CompletionOptions completionOptions = new CompletionOptions();
        completionOptions.setResolveProvider(false);
        List<String> triggerCharacters = waitForResultAndHandleExceptions(truffleAdapter.getCompletionTriggerCharacters());
        System.out.println("Completion trigger character set: " + triggerCharacters);
        completionOptions.setTriggerCharacters(triggerCharacters);
        capabilities.setCompletionProvider(completionOptions);
        capabilities.setCodeActionProvider(true);
        capabilities.setSignatureHelpProvider(signatureHelpOptions);
        capabilities.setHoverProvider(true);

        capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(Arrays.asList(ANALYSE_COVERAGE, SHOW_COVERAGE)));

        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    public CompletableFuture<Object> shutdown() {
        info.println("[Truffle LSP] Shutting down server...");
        return CompletableFuture.completedFuture(null);
    }

    public void exit() {
        try {
            serverSocket.close();
        } catch (IOException e) {
            err.println("[Truffle LSP] Error while closing socket: " + e.getLocalizedMessage());
        }
        executor.shutdownNow();
        info.println("[Truffle LSP] Server shutdown done.");
    }

    public TextDocumentService getTextDocumentService() {
        return this;
    }

    public WorkspaceService getWorkspaceService() {
        return this;
    }

    @Override
    public void connect(@SuppressWarnings("hiding") LanguageClient client) {
        this.client = client;
    }

    public void addDiagnostics(final URI uri, @SuppressWarnings("hiding") Diagnostic... diagnostics) {
        PublishDiagnosticsParams params = this.diagnostics.computeIfAbsent(uri, _uri -> {
            PublishDiagnosticsParams _params = new PublishDiagnosticsParams();
            _params.setUri(uri.toString());
            return _params;
        });

        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.getMessage() == null) {
                diagnostic.setMessage("<No message>");
            }
            params.getDiagnostics().add(diagnostic);
        }
    }

    public void reportCollectedDiagnostics(final String documentUri) {
        reportCollectedDiagnostics(documentUri, true);
    }

    public void reportCollectedDiagnostics() {
        for (Entry<URI, PublishDiagnosticsParams> entry : diagnostics.entrySet()) {
            reportCollectedDiagnostics(entry.getKey(), true);
        }
    }

    private void reportCollectedDiagnostics(final String documentUri, boolean forceIfNotExisting) {
        reportCollectedDiagnostics(URI.create(documentUri), forceIfNotExisting);
    }

    public void reportCollectedDiagnostics(final URI documentUri, boolean forceIfNotExisting) {
        if (diagnostics.containsKey(documentUri)) {
            client.publishDiagnostics(diagnostics.get(documentUri));
            diagnostics.remove(documentUri);
        } else if (forceIfNotExisting) {
            client.publishDiagnostics(new PublishDiagnosticsParams(documentUri.toString(), new ArrayList<>()));
        }
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams position) {
        Future<CompletionList> futureCompletionList = truffleAdapter.getCompletions(URI.create(position.getTextDocument().getUri()), position.getPosition().getLine(),
                        position.getPosition().getCharacter());
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Either.forRight(futureCompletionList.get());
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace(err);
                return Either.forRight(new CompletionList());
            }
        });
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public CompletableFuture<Hover> hover(TextDocumentPositionParams position) {
        Future<Hover> futureHover = truffleAdapter.getHover(URI.create(position.getTextDocument().getUri()), position.getPosition().getLine(), position.getPosition().getCharacter());
        return CompletableFuture.supplyAsync(() -> {
            try {
                return futureHover.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace(err);
                return new Hover();
            }
        });
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
        Future<SignatureHelp> future = truffleAdapter.signatureHelp(URI.create(position.getTextDocument().getUri()), position.getPosition().getLine(), position.getPosition().getCharacter());
        return CompletableFuture.supplyAsync(() -> {
            try {
                return future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace(err);
                return new SignatureHelp();
            }
        });
    }

    @Override
    public CompletableFuture<List<? extends Location>> definition(TextDocumentPositionParams position) {
        Future<List<? extends Location>> result = truffleAdapter.getDefinitions(URI.create(position.getTextDocument().getUri()), position.getPosition().getLine(),
                        position.getPosition().getCharacter());
        return CompletableFuture.supplyAsync(() -> {
            try {
                return result.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace(err);
                return new ArrayList<>();
            }
        });
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(TextDocumentPositionParams position) {
        List<? extends DocumentHighlight> highlights = truffleAdapter.getHighlights(URI.create(position.getTextDocument().getUri()), position.getPosition().getLine(),
                        position.getPosition().getCharacter());
        return CompletableFuture.supplyAsync(() -> highlights);
    }

    @Override
    public CompletableFuture<List<? extends SymbolInformation>> documentSymbol(DocumentSymbolParams params) {
        Future<List<? extends SymbolInformation>> future = truffleAdapter.getSymbolInfo(URI.create(params.getTextDocument().getUri()));
        List<? extends SymbolInformation> result = waitForResultAndHandleExceptions(future);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<List<? extends Command>> codeAction(CodeActionParams params) {
        List<Command> commands = new ArrayList<>();
        return CompletableFuture.completedFuture(commands);
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        CodeLens codeLens = new CodeLens(new Range(new Position(), new Position()));
        Command command = new Command("Analyse coverage", ANALYSE_COVERAGE);
        command.setArguments(Arrays.asList(params.getTextDocument().getUri()));
        codeLens.setCommand(command);

        CodeLens codeLensShowCoverage = new CodeLens(new Range(new Position(), new Position()));
        Command commandShowCoverage = new Command("Highlight uncovered code", SHOW_COVERAGE);
        commandShowCoverage.setArguments(Arrays.asList(params.getTextDocument().getUri()));
        codeLensShowCoverage.setCommand(commandShowCoverage);

        return CompletableFuture.completedFuture(Arrays.asList(codeLens, codeLensShowCoverage));
    }

    @Override
    public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        URI uri = URI.create(params.getTextDocument().getUri());
        openedFileUri2LangId.put(uri, params.getTextDocument().getLanguageId());

        truffleAdapter.didOpen(uri, params.getTextDocument().getText(), params.getTextDocument().getLanguageId());

        Future<Void> future = truffleAdapter.parse(params.getTextDocument().getText(), params.getTextDocument().getLanguageId(), URI.create(params.getTextDocument().getUri()));
        CompletableFuture.runAsync(() -> waitForResultAndHandleExceptions(future));
    }

    private <T> T waitForResultAndHandleExceptions(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            e.printStackTrace(err);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownLanguageException) {
                client.showMessage(new MessageParams(MessageType.Error, "Unknown language: " + e.getCause().getMessage()));
            } else {
                e.printStackTrace(err);
            }
        }

        return null;
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        processChanges(params.getTextDocument().getUri(), params.getContentChanges());
    }

    private void processChanges(final String documentUri,
                    final List<? extends TextDocumentContentChangeEvent> list) {
        String langId = openedFileUri2LangId.get(URI.create(documentUri));
        assert langId != null : documentUri;

        Future<Void> future;
        if (TEXT_DOCUMENT_SYNC_KIND.equals(TextDocumentSyncKind.Full)) {
            // Only need the first element, as long as sync mode is
            // TextDocumentSyncKind.Full
            TextDocumentContentChangeEvent e = list.iterator().next();
            final String langId1 = langId;

            future = truffleAdapter.parse(e.getText(), langId1, URI.create(documentUri));
        } else if (TEXT_DOCUMENT_SYNC_KIND.equals(TextDocumentSyncKind.Incremental)) {
            future = truffleAdapter.processChangesAndParse(list, URI.create(documentUri));
        } else {
            throw new IllegalStateException("Unknown TextDocumentSyncKind: " + TEXT_DOCUMENT_SYNC_KIND);
        }

        CompletableFuture.runAsync(() -> waitForResultAndHandleExceptions(future));
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        URI uri = URI.create(params.getTextDocument().getUri());
        String removed = openedFileUri2LangId.remove(uri);
        assert removed != null : uri.toString();

        truffleAdapter.didClose(uri);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // TODO Auto-generated method stub
    }

    @Override
    public CompletableFuture<List<? extends SymbolInformation>> symbol(WorkspaceSymbolParams params) {
        List<? extends SymbolInformation> result = new ArrayList<>();
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // TODO(ds) client configs are not used atm...
        if (params.getSettings() instanceof Map<?, ?>) {
            Map<?, ?> settings = (Map<?, ?>) params.getSettings();
            if (settings.get("truffleLsp") instanceof Map<?, ?>) {
                Map<?, ?> truffleLsp = (Map<?, ?>) settings.get("truffleLsp");
                if (truffleLsp.get("trace") instanceof Map<?, ?>) {
                    Map<?, ?> trace = (Map<?, ?>) truffleLsp.get("trace");
                    if (trace.get("server") instanceof String) {
                        trace_server = (String) trace.get("server");
                    }
                }
            }
        }
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    }

    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        if (ANALYSE_COVERAGE.equals(params.getCommand())) {
            client.showMessage(new MessageParams(MessageType.Info, "Running Coverage analysis..."));
            String uri = ((JsonPrimitive) params.getArguments().get(0)).getAsString();

            Future<Boolean> future = truffleAdapter.runCoverageAnalysis(URI.create(uri));
            boolean success = waitForResultAndHandleExceptions(future);

            if (success) {
                client.showMessage(new MessageParams(MessageType.Info, "Coverage analysis done."));
            } else {
                client.showMessage(new MessageParams(MessageType.Error, "Coverage analysis failed."));
            }
        } else if (SHOW_COVERAGE.equals(params.getCommand())) {
            String uri = ((JsonPrimitive) params.getArguments().get(0)).getAsString();

            try {
                // TODO(ds) wrap method in future
                truffleAdapter.showCoverage(URI.create(uri));
            } finally {
                reportCollectedDiagnostics(uri, true);
            }
        }

        return CompletableFuture.completedFuture(new Object());
    }

    public boolean isVerbose() {
        return "verbose".equals(trace_server);
    }

    public Future<?> start(@SuppressWarnings("hiding") final ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {

            public Thread newThread(Runnable r) {
                Thread thread = Executors.defaultThreadFactory().newThread(r);
                thread.setName("LSP client connection thread");
                return thread;
            }
        });
        Future<?> future = executor.submit(new Runnable() {

            public void run() {
// while (true) {
                try {
                    if (serverSocket.isClosed()) {
                        err.println("[Truffle LSP] Server socket is closed.");
                        return;
                    }

                    info.println("[Truffle LSP] Starting server and listening on " + serverSocket.getLocalSocketAddress());
                    Socket clientSocket = serverSocket.accept();
                    info.println("[Truffle LSP] Client connected on " + clientSocket.getRemoteSocketAddress());

                    ExecutorService lspRequestExecutor = Executors.newCachedThreadPool(new ThreadFactory() {
                        private final ThreadFactory factory = Executors.defaultThreadFactory();

                        public Thread newThread(Runnable r) {
                            Thread thread = factory.newThread(r);
                            thread.setName("LSP client request handler " + thread.getName());
                            return thread;
                        }
                    });

                    //@formatter:off
                    Launcher<LanguageClient> launcher = new LSPLauncher.Builder<LanguageClient>()
                        .setLocalService(LSPServer.this)
                        .setRemoteInterface(LanguageClient.class)
                        .setInput(clientSocket.getInputStream())
                        .setOutput(clientSocket.getOutputStream())
                        .setExecutorService(lspRequestExecutor)
                      //.traceMessages(new PrintWriter(System.err, true))
                        .create();
                    //@formatter:on

                    LSPServer.this.connect(launcher.getRemoteProxy());
                    Future<?> listenFuture = launcher.startListening();
                    try {
                        listenFuture.get();
                    } catch (InterruptedException | ExecutionException e) {
                        err.println("[Truffle LSP] Error: " + e.getLocalizedMessage());
                    } finally {
                        lspRequestExecutor.shutdownNow();
                    }
                } catch (IOException e) {
                    err.println("[Truffle LSP] Error while connecting to client: " + e.getLocalizedMessage());
                }
// }
            }
        }, Boolean.TRUE);
        return future;
    }
}
