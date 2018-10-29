package de.hpi.swa.trufflelsp.server.utils;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.graalvm.options.OptionValues;

import com.oracle.truffle.api.TruffleException;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter.SourcePredicate;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

public class SourceUtils {

    public static final class SourceFix {
        public final String text;
        public final String removedCharacters;
        public final int characterIdx;

        public SourceFix(String text, String removedChracters, int characterIdx) {
            this.text = text;
            this.removedCharacters = removedChracters;
            this.characterIdx = characterIdx;
        }
    }

    public static boolean isLineValid(int zeroBasedLine, Source source) {
        // Source line is one-based
        return zeroBasedLine >= 0 &&
                        (zeroBasedLine < source.getLineCount() ||
                                        (zeroBasedLine == source.getLineCount() && endsWithNewline(source)) ||
                                        (zeroBasedLine == 0 && source.getLineCount() == 0));
    }

    public static boolean isColumnValid(int line, int column, Source source) {
        return column <= source.getLineLength(zeroBasedLineToOneBasedLine(line, source));
    }

    public static int zeroBasedLineToOneBasedLine(int line, Source source) {
        if (source.getLineCount() < line) {
            System.err.println("Warning: Line is out of range: " + line);
        }

        return line + 1;
    }

    private static boolean endsWithNewline(Source source) {
        String text = source.getCharacters().toString();
        boolean isNewlineEnd = !text.isEmpty() && text.charAt(text.length() - 1) == '\n';
        return isNewlineEnd;
    }

    public static Range sourceSectionToRange(SourceSection section) {
        if (section == null) {
            return new Range(new Position(), new Position());
        }
        int endColumn = section.getEndColumn();
        if (section.getCharacters().toString().endsWith("\n")) {
            // TODO(ds) Python problem - without correction, goto definition highlighting is not
            // working
            endColumn -= 1;
        }
        return new Range(
                        new Position(section.getStartLine() - 1, section.getStartColumn() - 1),
                        new Position(section.getEndLine() - 1, endColumn));
    }

    public static SourceSection findSourceLocation(TruffleInstrument.Env env, String langId, Object object) {
        LanguageInfo languageInfo = env.findLanguage(object);
        if (languageInfo == null) {
            languageInfo = env.getLanguages().get(langId);
        }

        SourceSection sourceSection = null;
        if (languageInfo != null) {
            sourceSection = env.findSourceLocation(languageInfo, object);
        }
        return sourceSection;
    }

    public static SourceFix removeLastTextInsertion(TextDocumentSurrogate surrogate, int originalCharacter) {
        TextDocumentContentChangeEvent lastChange = surrogate.getLastChange();
        Range range = lastChange.getRange();
        TextDocumentContentChangeEvent replacementEvent = new TextDocumentContentChangeEvent(
                        new Range(range.getStart(), new Position(range.getEnd().getLine(), range.getEnd().getCharacter() + lastChange.getText().length())), lastChange.getText().length(), "");
        String codeBeforeLastChange = applyTextDocumentChanges(Arrays.asList(replacementEvent), surrogate.getEditorText(), surrogate);
        int characterIdx = originalCharacter - (originalCharacter - range.getStart().getCharacter());

        return new SourceFix(codeBeforeLastChange, lastChange.getText(), characterIdx);
    }

    public static String applyTextDocumentChanges(List<? extends TextDocumentContentChangeEvent> list, String text, TextDocumentSurrogate surrogate) {
        StringBuilder sb = new StringBuilder(text);
        for (TextDocumentContentChangeEvent event : list) {
            Range range = event.getRange();
            if (range == null) {
                // The whole file has changed
                sb.setLength(0); // Clear StringBuilder
                sb.append(event.getText());
                continue;
            }

            TextMap textMap = TextMap.fromCharSequence(sb);
            Position start = range.getStart();
            Position end = range.getEnd();
            int startLine = start.getLine() + 1;
            int endLine = end.getLine() + 1;
            int replaceBegin;
            int replaceEnd;
            if (textMap.lineCount() < startLine) {
                assert start.getCharacter() == 0 : start.getCharacter();
                assert textMap.finalNL || textMap.lineCount() == 0;
                assert textMap.lineCount() < endLine;
                assert end.getCharacter() == 0 : end.getCharacter();

                replaceBegin = textMap.length();
                replaceEnd = replaceBegin;
            } else if (textMap.lineCount() < endLine) {
                replaceBegin = textMap.lineStartOffset(startLine) + start.getCharacter();
                replaceEnd = text.length();
            } else {
                replaceBegin = textMap.lineStartOffset(startLine) + start.getCharacter();
                replaceEnd = textMap.lineStartOffset(endLine) + end.getCharacter();
            }
            sb.replace(replaceBegin, replaceEnd, event.getText());

            if (surrogate != null && surrogate.hasCoverageData()) {
                updateCoverageData(surrogate, text, event.getText(), range, replaceBegin, replaceEnd);
            }
        }
        return sb.toString();
    }

    private static void updateCoverageData(TextDocumentSurrogate surrogate, String text, String newText, Range range, int replaceBegin, int replaceEnd) {
        TextMap textMapNewText = TextMap.fromCharSequence(newText);
        int linesNewText = textMapNewText.lineCount() + (textMapNewText.finalNL ? 1 : 0) + (newText.isEmpty() ? 1 : 0);
        String oldText = text.substring(replaceBegin, replaceEnd);
        TextMap textMapOldText = TextMap.fromCharSequence(oldText);
        int liensOldText = textMapOldText.lineCount() + (textMapOldText.finalNL ? 1 : 0) + (oldText.isEmpty() ? 1 : 0);
        int newLineModification = linesNewText - liensOldText;
        System.out.println("newLineModification: " + newLineModification);

        if (newLineModification != 0) {
            List<SourceLocation> locations = surrogate.getCoverageLocations();
            locations.stream().filter(location -> location.includes(range)).forEach(location -> {
                SourceLocation fixedLocation = new SourceLocation(location);
                fixedLocation.setEndLine(fixedLocation.getEndLine() + newLineModification);
                surrogate.replace(location, fixedLocation);
                System.out.println("Inlcuded - Old: " + location + " Fixed: " + fixedLocation);
            });
            locations.stream().filter(location -> location.behind(range)).forEach(location -> {
                SourceLocation fixedLocation = new SourceLocation(location);
                fixedLocation.setStartLine(fixedLocation.getStartLine() + newLineModification);
                fixedLocation.setEndLine(fixedLocation.getEndLine() + newLineModification);
                surrogate.replace(location, fixedLocation);
                System.out.println("Behind   - Old: " + location + " Fixed: " + fixedLocation);
            });
        }
    }

    public static Range getRangeFrom(TruffleException te) {
        Range range = new Range(new Position(), new Position());
        SourceSection sourceLocation = te.getSourceLocation() != null ? te.getSourceLocation()
                        : (te.getLocation() != null ? te.getLocation().getEncapsulatingSourceSection() : null);
        if (sourceLocation != null && sourceLocation.isAvailable()) {
            range = sourceSectionToRange(sourceLocation);
        }
        return range;
    }

    public static int convertLineAndColumnToOffset(Source source, int oneBasedLineNumber, int column) {
        int offset = source.getLineStartOffset(oneBasedLineNumber);
        if (column > 0) {
            offset += column - 1;
        }
        return offset;
    }

    public static URI getOrFixFileUri(Source source) {
        if (source.getURI().getScheme().equals("file")) {
            return source.getURI();
        } else if (source.getURI().getScheme().equals("truffle")) {
            // We assume, that the source name is a valid file path if
            // the URI has no file scheme
            Path path = Paths.get(source.getName());
            return path.toUri();
        } else {
            throw new IllegalStateException("Source has an URI with unknown schema: " + source.getURI());
        }
    }

    public static boolean isValidSourceSection(SourceSection sourceSection, OptionValues options) {
        SourcePredicate predicate = SourcePredicateBuilder.newBuilder().excludeInternal(options).build();
        return sourceSection != null && sourceSection.isAvailable() && predicate.test(sourceSection.getSource());
    }

}
