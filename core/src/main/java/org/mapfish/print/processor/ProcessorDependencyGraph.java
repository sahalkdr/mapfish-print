package org.mapfish.print.processor;

import com.google.common.base.Optional;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

import org.mapfish.print.output.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RecursiveTask;

import javax.annotation.Nonnull;

import static org.mapfish.print.parser.ParserUtils.FILTER_ONLY_REQUIRED_ATTRIBUTES;
import static org.mapfish.print.parser.ParserUtils.getAttributeNames;

/**
 * Represents a graph of the processors dependencies.  The root nodes can execute in parallel but processors with
 * dependencies must wait for their dependencies to complete before execution.
 * <p></p>
 */
public final class ProcessorDependencyGraph {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessorDependencyGraph.class);
    private final List<ProcessorGraphNode> roots;

    ProcessorDependencyGraph() {
        this.roots = new ArrayList<ProcessorGraphNode>();
    }

    /**
     * Create a ForkJoinTask for running in a fork join pool.
     *
     * @param values the values to use for getting the required inputs of the processor and putting the output in.
     * @return a task ready to be submitted to a fork join pool.
     */
    public ProcessorGraphForkJoinTask createTask(@Nonnull final Values values) {
        StringBuilder missingAttributes = new StringBuilder();
        final Multimap<String, Processor> requiredAttributes = getAllRequiredAttributes();
        for (String attribute : requiredAttributes.keySet()) {
            if (!values.containsKey(attribute)) {
                missingAttributes.append("\n\t* ").append(attribute).append(" <- ").append(requiredAttributes.get(attribute));
            }
        }

        if (missingAttributes.length() > 0) {
            throw new IllegalArgumentException("It has been found that one or more required attributes are " +
                    "missing from the values object:" + missingAttributes + "\n");
        }

        return new ProcessorGraphForkJoinTask(values);
    }

    /**
     * Add a new root node.
     *
     * @param node new root node.
     */
    void addRoot(final ProcessorGraphNode node) {
        this.roots.add(node);
    }

    /**
     * Get all the names of inputs that are required to be in the Values object when this graph is executed.
     */
    @SuppressWarnings("unchecked")
    public Multimap<String, Processor> getAllRequiredAttributes() {
        Multimap<String, Processor> requiredInputs = HashMultimap.create();
        for (ProcessorGraphNode root : this.roots) {
            final BiMap<String, String> inputMapper = root.getInputMapper();
            for (String attr : inputMapper.keySet()) {
                requiredInputs.put(attr, root.getProcessor());
            }
            final Object inputParameter = root.getProcessor().createInputParameter();
            if (inputParameter instanceof Values) {
                continue;
            } else if (inputParameter != null) {
                final Class<?> inputParameterClass = inputParameter.getClass();
                final Set<String> requiredAttributesDefinedInInputParameter = getAttributeNames(inputParameterClass,
                        FILTER_ONLY_REQUIRED_ATTRIBUTES);
                for (String attName : requiredAttributesDefinedInInputParameter) {
                    try {
                        if (inputParameterClass.getField(attName).getType() == Values.class) {
                            continue;
                        }
                    } catch (NoSuchFieldException e) {
                        throw new RuntimeException(e);
                    }
                    String mappedName = ProcessorUtils.getInputValueName(
                            root.getProcessor().getInputPrefix(),
                            inputMapper, attName);
                    requiredInputs.put(mappedName, root.getProcessor());
                }
            }
        }

        return requiredInputs;
    }

    public List<ProcessorGraphNode> getRoots() {
        return this.roots;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (ProcessorGraphNode root : this.roots) {
            if (this.roots.indexOf(root) != 0) {
                builder.append('\n');
            }
            builder.append("+ ");
            root.toString(builder, 0);
        }
        return builder.toString();
    }

    /**
     * Create a set containing all the processors in the graph.
     */
    public Set<Processor<?, ?>> getAllProcessors() {
        IdentityHashMap<Processor<?, ?>, Void> all = new IdentityHashMap<Processor<?, ?>, Void>();
        for (ProcessorGraphNode<?, ?> root : this.roots) {
            for (Processor p : root.getAllProcessors()) {
                all.put(p, null);
            }
        }
        return all.keySet();
    }

    /**
     * A ForkJoinTask that will create ForkJoinTasks from each root and run each of them.
     */
    public final class ProcessorGraphForkJoinTask extends RecursiveTask<Values> {
        private final ProcessorExecutionContext execContext;

        private ProcessorGraphForkJoinTask(@Nonnull final Values values) {
            this.execContext = new ProcessorExecutionContext(values);
        }

        /**
         * Cancels the complete processor graph of a print task.
         *
         * This is achieved by setting the cancel flag of the execution
         * context, so that every processor can stop its execution.
         *
         * @param mayInterruptIfRunning is ignored
         */
        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {
            this.execContext.cancel();
            return super.cancel(mayInterruptIfRunning);
        }

        @Override
        protected Values compute() {
            final ProcessorDependencyGraph graph = ProcessorDependencyGraph.this;
            MDC.put("job_id", this.execContext.getJobId());
            LOGGER.debug("Starting to execute processor graph: \n" + graph);
            try {
                List<ProcessorGraphNode.ProcessorNodeForkJoinTask> tasks = Lists.newArrayListWithExpectedSize(graph.roots.size());
                // fork all but 1 dependencies (the first will be ran in current thread)
                for (int i = 0; i < graph.roots.size(); i++) {
                    @SuppressWarnings("unchecked")
                    Optional<ProcessorGraphNode.ProcessorNodeForkJoinTask> task = graph.roots.get(i).createTask(this.execContext);
                    if (task.isPresent()) {
                        tasks.add(task.get());
                        if (tasks.size() > 1) {
                            task.get().fork();
                        }
                    }
                }

                if (!tasks.isEmpty()) {
                    // compute one task in current thread so as not to waste threads
                    tasks.get(0).compute();

                    for (ProcessorGraphNode.ProcessorNodeForkJoinTask task : tasks.subList(1, tasks.size())) {
                        task.join();
                    }
                }
            } finally {
                LOGGER.debug("Finished executing processor graph: \n" + graph);
            }
            return this.execContext.getValues();
        }
    }
}
