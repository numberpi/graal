package de.hpi.swa.trufflelsp.server;

import static org.junit.Assert.assertEquals;

import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.Test;

public class HoverTest extends TruffleLSPTest {

    @Test
    public void hoverNoCoverageDataAvailable() throws InterruptedException, ExecutionException {
        URI uri = createDummyFileUriForSL();
        //@formatter:off
        /**
         *  0 function main() {
         *  1     abc();
         *  2     x = abc();
         *  3 }
         *  4
         *  5 function abc() {
         *  6   obj = new();
         *  7   obj.p = 1;
         *  8   return obj;
         *  9 }
         * 10
         */
        //@formatter:on
        String text = "function main() {\n    abc();\n    x = abc();\n}\n\nfunction abc() {\n  obj = new();\n  obj.p = 1;\n  return obj;\n}\n";
        Future<?> futureOpen = truffleAdapter.parse(text, "sl", uri);
        futureOpen.get();

        int line = 1;
        int column = 5;
        {
            Future<Hover> future = truffleAdapter.hover(uri, line, column);
            Hover hover = future.get();
            assertEquals(new Range(new Position(1, 4), new Position(1, 7)), hover.getRange());
        }

        line = 1;
        column = 8;
        {
            Future<Hover> future = truffleAdapter.hover(uri, line, column);
            Hover hover = future.get();
            assertEquals(new Range(new Position(1, 4), new Position(1, 9)), hover.getRange());
        }

        line = 0;
        column = 10;
        {
            Future<Hover> future = truffleAdapter.hover(uri, line, column);
            Hover hover = future.get();
            assertEquals(new Range(new Position(0, 9), new Position(3, 1)), hover.getRange());
        }
    }

}
