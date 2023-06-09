package example;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.*;

public class FoobarValueAnalyzer implements Plugin {
    private static class InternalTaskListener implements TaskListener {
        private final JavacTask task;

        public InternalTaskListener(JavacTask task) {
            this.task = task;
        }

        @Override
        public void started(TaskEvent event) {
            if (event.getKind() == TaskEvent.Kind.ANALYZE) {
                Trees trees = Trees.instance(task);
                System.out.println("Starting analysis ... ");
                Iterable<? extends CompilationUnitTree> compilationUnits;
                try {
                    compilationUnits = task.parse();
                    for (CompilationUnitTree compilationUnit : compilationUnits) {
                        System.out.println("Analyzing " + compilationUnit.getSourceFile());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void finished(TaskEvent event) {
            System.out.println("Finished");
        }
    }

    @Override
    public String getName() {
        return "FoobarValueAnalyzer";
    }

    @Override
    public void init(JavacTask task, String... args) {
        task.addTaskListener(new InternalTaskListener(task));
    }
}
