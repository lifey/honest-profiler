/**
 * Copyright (c) 2014 Richard Warburton (richard.warburton@gmail.com)
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a 
 * copy of this software and associated documentation files (the "Software"), 
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, 
 * and/or sell copies of the Software, and to permit persons to whom the 
 * Software is furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS 
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING 
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER 
 * DEALINGS IN THE SOFTWARE.
 **/
package com.insightfullogic.honest_profiler.ports.sources;

import com.insightfullogic.honest_profiler.core.MachineListener;
import com.insightfullogic.honest_profiler.core.ThreadedAgent;
import com.insightfullogic.honest_profiler.core.sources.VirtualMachine;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachineDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class LocalMachineSource {

    private static final String VM_ARGS = "sun.jvm.args";
    private static final String AGENT_NAME = "liblagent.so";
    private static final String USER_DIR = "user.dir";
    private static final long DEFAULT_SLEEP_PERIOD = 500;

    private final Logger logger;
    private MachineListener listener;
    private final long sleepPeriod;
    private final ThreadedAgent threadedAgent;

    private Set<VirtualMachineDescriptor> previous;

    public LocalMachineSource(final Logger logger, final MachineListener listener) {
        this(logger, listener, DEFAULT_SLEEP_PERIOD);
    }

    public LocalMachineSource(final Logger logger, final MachineListener listener, final long sleepPeriod) {
        this.logger = logger;
        this.listener = listener;
        this.sleepPeriod = sleepPeriod;
        previous = new HashSet<>();
        threadedAgent = new ThreadedAgent(LoggerFactory.getLogger(ThreadedAgent.class), this::discoverVirtualMachines);
    }

    @PostConstruct
    public void start() {
        threadedAgent.start();
    }

    public boolean discoverVirtualMachines() {
        poll();

        sleep();

        return true;
    }

    private void sleep() {
        try {
            Thread.sleep(sleepPeriod);
        } catch (InterruptedException e) {
            // Ignore
        }
    }

    private void poll() {
        Set<VirtualMachineDescriptor> current = new HashSet<>(com.sun.tools.attach.VirtualMachine.list());
        difference(current, previous, listener::onNewMachine);
        difference(previous, current, listener::onClosedMachine);
        previous = current;
    }

    private void difference(
            Set<VirtualMachineDescriptor> left,
            Set<VirtualMachineDescriptor> right,
            Consumer<VirtualMachine> action) {

        // TODO: only attach once per vm
        left.stream()
            .filter(vm -> !right.contains(vm))
            .flatMap(this::attach)
            .forEach(action);
    }

    private Stream<VirtualMachine> attach(VirtualMachineDescriptor vmDescriptor) {
        try {
            com.sun.tools.attach.VirtualMachine vm = com.sun.tools.attach.VirtualMachine.attach(vmDescriptor);
            String vmArgs = vm.getAgentProperties().getProperty(VM_ARGS);

            String id = vmDescriptor.id();
            String displayName = vmDescriptor.displayName();
            boolean agentLoaded = vmArgs.contains(AGENT_NAME);
            String userDir = getUserDir(vm);
            return Stream.of(new VirtualMachine(id, displayName, agentLoaded, userDir));
        } catch (AttachNotSupportedException e) {
            logger.warn(e.getMessage());
        } catch (IOException e) {
            if (!noSuchProcess(e)) {
                logger.warn(e.getMessage(), e);
            }
        }
        return Stream.empty();
    }

    private String getUserDir(com.sun.tools.attach.VirtualMachine vm) throws IOException {
        final String userDir = vm.getAgentProperties().getProperty(USER_DIR);
        if (userDir != null)
            return userDir;

        return vm.getSystemProperties().getProperty(USER_DIR);
    }

    private boolean noSuchProcess(IOException e) {
        return e.getMessage().contains("No such process");
    }

    @PreDestroy
    public void stop() {
        threadedAgent.stop();
    }

}
