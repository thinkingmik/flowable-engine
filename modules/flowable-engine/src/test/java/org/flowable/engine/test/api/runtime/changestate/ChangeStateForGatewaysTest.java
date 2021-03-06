/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.flowable.engine.test.api.runtime.changestate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.engine.delegate.event.FlowableActivityEvent;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.test.PluggableFlowableTestCase;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.test.Deployment;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * @author Frederik Heremans
 * @author Joram Barrez
 * @author Dennis Federico
 */
public class ChangeStateForGatewaysTest extends PluggableFlowableTestCase {

    private ChangeStateEventListener changeStateEventListener = new ChangeStateEventListener();

    @BeforeEach
    protected void setUp() {
        processEngine.getRuntimeService().addEventListener(changeStateEventListener);
    }

    @AfterEach
    protected void tearDown() {
        processEngine.getRuntimeService().removeEventListener(changeStateEventListener);
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelTask.bpmn20.xml" })
    public void testSetCurrentActivityToMultipleActivitiesForParallelGateway() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        List<String> newActivityIds = new ArrayList<>();
        newActivityIds.add("task1");
        newActivityIds.add("task2");

        changeStateEventListener.clear();

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveSingleActivityIdToActivityIds("taskBefore", newActivityIds)
                .changeState();

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task1", "task2");
        assertThat(executionsByActivity).doesNotContainKey("parallelJoin");

        //Complete one task1
        Optional<Task> task1 = tasks.stream().filter(t -> t.getTaskDefinitionKey().equals("task1")).findFirst();
        if (task1.isPresent()) {
            taskService.complete(task1.get().getId());
        }

        // Verify events
        Iterator<FlowableEvent> iterator = changeStateEventListener.iterator();
        assertThat(iterator.hasNext()).isTrue();

        FlowableEvent event = iterator.next();
        assertThat(event.getType()).isEqualTo(FlowableEngineEventType.ACTIVITY_CANCELLED);
        assertThat(((FlowableActivityEvent) event).getActivityId()).isEqualTo("taskBefore");

        assertThat(iterator.hasNext()).isTrue();
        event = iterator.next();
        assertThat(event.getType()).isEqualTo(FlowableEngineEventType.ACTIVITY_STARTED);
        assertThat(((FlowableActivityEvent) event).getActivityId()).isEqualTo("task1");

        assertThat(iterator.hasNext()).isTrue();
        event = iterator.next();
        assertThat(event.getType()).isEqualTo(FlowableEngineEventType.ACTIVITY_STARTED);
        assertThat(((FlowableActivityEvent) event).getActivityId()).isEqualTo("task2");

        assertThat(!iterator.hasNext()).isTrue();

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(1);

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).doesNotContainKey("task1");
        assertThat(executionsByActivity).containsKeys("task2", "parallelJoin");

        assertThat(((ExecutionEntity) executionsByActivity.get("parallelJoin").get(0)).isActive()).isFalse();

        taskService.complete(tasks.get(0).getId());

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");
        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelTask.bpmn20.xml" })
    public void testSetMultipleActivitiesToSingleActivityAfterParallelGateway() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        List<String> currentActivityIds = new ArrayList<>();
        currentActivityIds.add("task1");
        currentActivityIds.add("task2");

        changeStateEventListener.clear();

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdsToSingleActivityId(currentActivityIds, "taskAfter")
                .changeState();

        // Verify events
        Iterator<FlowableEvent> iterator = changeStateEventListener.iterator();
        assertThat(iterator.hasNext()).isTrue();

        FlowableEvent event = iterator.next();
        assertThat(event.getType()).isEqualTo(FlowableEngineEventType.ACTIVITY_CANCELLED);
        assertThat(((FlowableActivityEvent) event).getActivityId()).isEqualTo("task1");

        assertThat(iterator.hasNext()).isTrue();
        event = iterator.next();
        assertThat(event.getType()).isEqualTo(FlowableEngineEventType.ACTIVITY_CANCELLED);
        assertThat(((FlowableActivityEvent) event).getActivityId()).isEqualTo("task2");

        assertThat(iterator.hasNext()).isTrue();
        event = iterator.next();
        assertThat(event.getType()).isEqualTo(FlowableEngineEventType.ACTIVITY_STARTED);
        assertThat(((FlowableActivityEvent) event).getActivityId()).isEqualTo("taskAfter");

        assertThat(!iterator.hasNext()).isTrue();

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");
        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelTask.bpmn20.xml" })
    public void testSetMultipleActivitiesIntoSynchronizingParallelGateway() {

        //Move all parallelGateway activities to the synchronizing gateway
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsOnlyKeys("task1", "task2");

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdsToSingleActivityId(Arrays.asList("task1", "task2"), "parallelJoin")
                .changeState();

        Execution execution = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().singleResult();
        assertThat(execution.getActivityId()).isEqualTo("taskAfter");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelTask.bpmn20.xml" })
    public void testSetMultipleGatewayActivitiesAndSynchronizingParallelGatewayAfterGateway() {

        //Move all parallelGateway activities to the synchronizing gateway
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task1", "task2");

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        //Complete task1
        for (Task t : tasks) {
            if (t.getTaskDefinitionKey().equals("task1")) {
                taskService.complete(t.getId());
            }
        }

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> classifiedExecutions = groupListContentBy(executions, Execution::getActivityId);
        assertThat(classifiedExecutions.get("task2")).isNotNull();
        assertThat(classifiedExecutions.get("task2")).hasSize(1);
        assertThat(classifiedExecutions.get("parallelJoin")).isNotNull();
        assertThat(classifiedExecutions.get("parallelJoin")).hasSize(1);

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdsToSingleActivityId(Arrays.asList("task2", "parallelJoin"), "taskAfter")
                .changeState();

        Execution execution = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().singleResult();
        assertThat(execution.getActivityId()).isEqualTo("taskAfter");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelTask.bpmn20.xml" })
    public void testSetActivityIntoSynchronizingParallelGatewayFirst() {

        //Move one task to the synchronizing gateway, then complete the remaining task
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdTo("task1", "parallelJoin")
                .changeState();

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).doesNotContainKey("task1");
        assertThat(executionsByActivity).containsKeys("task2", "parallelJoin");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("task2");

        taskService.complete(task.getId());

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelTask.bpmn20.xml" })
    public void testSetActivityIntoSynchronizingParallelGatewayLast() {

        //Complete one task and then move the last remaining task to the synchronizing gateway
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        //Complete task1
        Optional<Task> task1 = tasks.stream().filter(t -> t.getTaskDefinitionKey().equals("task1")).findFirst();
        if (task1.isPresent()) {
            taskService.complete(task1.get().getId());
        }

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).doesNotContainKey("task1");
        assertThat(executionsByActivity).containsKeys("task2", "parallelJoin");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("task2");

        //Move task2
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdTo("task2", "parallelJoin")
                .changeState();

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelTask.bpmn20.xml" })
    public void testSetCurrentExecutionToMultipleActivitiesForParallelGateway() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        Execution taskBeforeExecution = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().singleResult();

        List<String> newActivityIds = new ArrayList<>();
        newActivityIds.add("task1");
        newActivityIds.add("task2");

        changeStateEventListener.clear();

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveSingleExecutionToActivityIds(taskBeforeExecution.getId(), newActivityIds)
                .changeState();

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task1", "task2");
        assertThat(executionsByActivity).doesNotContainKey("parallelJoin");

        //Complete one task1
        Optional<Task> task1 = tasks.stream().filter(t -> t.getTaskDefinitionKey().equals("task1")).findFirst();
        if (task1.isPresent()) {
            taskService.complete(task1.get().getId());
        }

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(1);

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task2", "parallelJoin");
        assertThat(executionsByActivity).doesNotContainKey("task1");

        assertThat(((ExecutionEntity) executionsByActivity.get("parallelJoin").get(0)).isActive()).isFalse();

        taskService.complete(tasks.get(0).getId());

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");
        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelTask.bpmn20.xml" })
    public void testSetMultipleExecutionsToSingleActivityAfterParallelGateway() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        List<String> currentExecutionIds = new ArrayList<>();
        currentExecutionIds.add(executions.get(0).getId());
        currentExecutionIds.add(executions.get(1).getId());
        runtimeService.createChangeActivityStateBuilder()
                .moveExecutionsToSingleActivityId(currentExecutionIds, "taskAfter")
                .changeState();

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");
        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelTask.bpmn20.xml" })
    public void testSetMultipleExecutionsIntoSynchronizingParallelGateway() {

        //Move all gateway executions to the synchronizing gateway
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task1", "task2");

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<String> executionIds = executions.stream().map(Execution::getId).collect(Collectors.toList());

        runtimeService.createChangeActivityStateBuilder()
                .moveExecutionsToSingleActivityId(executionIds, "parallelJoin")
                .changeState();

        Execution execution = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().singleResult();
        assertThat(execution.getActivityId()).isEqualTo("taskAfter");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelTask.bpmn20.xml" })
    public void testSetMultipleGatewayExecutionsAndSynchronizingParallelGatewayAfterGateway() {

        //Move all gateway executions to the synchronizing gateway
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task1", "task2");

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        //Complete task1
        for (Task t : tasks) {
            if (t.getTaskDefinitionKey().equals("task1")) {
                taskService.complete(t.getId());
            }
        }

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> classifiedExecutions = groupListContentBy(executions, Execution::getActivityId);
        assertThat(classifiedExecutions.get("task2")).isNotNull();
        assertThat(classifiedExecutions.get("task2")).hasSize(1);
        assertThat(classifiedExecutions.get("parallelJoin")).isNotNull();
        assertThat(classifiedExecutions.get("parallelJoin")).hasSize(1);

        List<String> executionIds = executions.stream().map(Execution::getId).collect(Collectors.toList());

        runtimeService.createChangeActivityStateBuilder()
                .moveExecutionsToSingleActivityId(executionIds, "taskAfter")
                .changeState();

        Execution execution = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().singleResult();
        assertThat(execution.getActivityId()).isEqualTo("taskAfter");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelTask.bpmn20.xml" })
    public void testSetExecutionIntoSynchronizingParallelGatewayFirst() {

        //Move one task to the synchronizing gateway, then complete the remaining task
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        String executionId = executions.stream().filter(e -> "task1".equals(e.getActivityId())).findFirst().map(Execution::getId).get();
        runtimeService.createChangeActivityStateBuilder()
                .moveExecutionToActivityId(executionId, "parallelJoin")
                .changeState();

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task2", "parallelJoin");
        assertThat(executionsByActivity).doesNotContainKey("task1");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("task2");

        taskService.complete(task.getId());

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelTask.bpmn20.xml" })
    public void testSetExecutionIntoSynchronizingParallelGatewayLast() {

        //Complete one task and then move the last remaining task to the synchronizing gateway
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        //Complete task1
        Optional<Task> task1 = tasks.stream().filter(t -> t.getTaskDefinitionKey().equals("task1")).findFirst();
        if (task1.isPresent()) {
            taskService.complete(task1.get().getId());
        }

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task2", "parallelJoin");
        assertThat(executionsByActivity).doesNotContainKey("task1");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("task2");

        //Move task2 execution
        String executionId = executions.stream().filter(e -> "task2".equals(e.getActivityId())).findFirst().map(Execution::getId).get();

        runtimeService.createChangeActivityStateBuilder()
                .moveExecutionToActivityId(executionId, "parallelJoin")
                .changeState();

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/inclusiveGatewayForkJoin.bpmn20.xml" })
    public void testSetCurrentActivityToMultipleActivitiesForInclusiveGateway() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startInclusiveGwProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        List<String> newActivityIds = new ArrayList<>();
        newActivityIds.add("task1");
        newActivityIds.add("task2");

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveSingleActivityIdToActivityIds("taskBefore", newActivityIds)
                .changeState();

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task1", "task2");
        assertThat(executionsByActivity).doesNotContainKey("gwJoin");

        //Complete one task1
        Optional<Task> task1 = tasks.stream().filter(t -> t.getTaskDefinitionKey().equals("task1")).findFirst();
        if (task1.isPresent()) {
            taskService.complete(task1.get().getId());
        }

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(1);

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task2", "gwJoin");
        assertThat(executionsByActivity).doesNotContainKey("task1");

        assertThat(((ExecutionEntity) executionsByActivity.get("gwJoin").get(0)).isActive()).isFalse();

        taskService.complete(tasks.get(0).getId());

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");
        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/inclusiveGatewayForkJoin.bpmn20.xml" })
    public void testSetMultipleActivitiesToSingleActivityAfterInclusiveGateway() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startInclusiveGwProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        List<String> currentActivityIds = new ArrayList<>();
        currentActivityIds.add("task1");
        currentActivityIds.add("task2");

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdsToSingleActivityId(currentActivityIds, "taskAfter")
                .changeState();

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/inclusiveGatewayForkJoin.bpmn20.xml" })
    public void testSetMultipleActivitiesIntoSynchronizingInclusiveGateway() {

        //Move all parallelGateway activities to the synchronizing gateway
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startInclusiveGwProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task1", "task2");

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdsToSingleActivityId(Arrays.asList("task1", "task2"), "gwJoin")
                .changeState();

        Execution execution = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().singleResult();
        assertThat(execution.getActivityId()).isEqualTo("taskAfter");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/inclusiveGatewayForkJoin.bpmn20.xml" })
    public void testSetMultipleGatewayActivitiesAndSynchronizingInclusiveGatewayAfterGateway() {

        //Move all parallelGateway activities to the synchronizing gateway
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startInclusiveGwProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task1", "task2");

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        //Complete task1
        for (Task t : tasks) {
            if (t.getTaskDefinitionKey().equals("task1")) {
                taskService.complete(t.getId());
            }
        }

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> classifiedExecutions = groupListContentBy(executions, Execution::getActivityId);
        assertThat(classifiedExecutions.get("task2")).isNotNull();
        assertThat(classifiedExecutions.get("task2")).hasSize(1);
        assertThat(classifiedExecutions.get("gwJoin")).isNotNull();
        assertThat(classifiedExecutions.get("gwJoin")).hasSize(1);

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdsToSingleActivityId(Arrays.asList("task2", "gwJoin"), "taskAfter")
                .changeState();

        Execution execution = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().singleResult();
        assertThat(execution.getActivityId()).isEqualTo("taskAfter");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/inclusiveGatewayForkJoin.bpmn20.xml" })
    public void testSetActivityIntoSynchronizingParallelInclusiveFirst() {

        //Move one task to the synchronizing gateway, then complete the remaining task
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startInclusiveGwProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdTo("task1", "gwJoin")
                .changeState();

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task2", "gwJoin");
        assertThat(executionsByActivity).doesNotContainKey("task1");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();

        assertThat(task.getTaskDefinitionKey()).isEqualTo("task2");

        taskService.complete(task.getId());

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/inclusiveGatewayForkJoin.bpmn20.xml" })
    public void testSetActivityIntoSynchronizingParallelInclusiveLast() {

        //Complete one task and then move the last remaining task to the synchronizing gateway
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startInclusiveGwProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        //Complete task1
        Optional<Task> task1 = tasks.stream().filter(t -> t.getTaskDefinitionKey().equals("task1")).findFirst();
        if (task1.isPresent()) {
            taskService.complete(task1.get().getId());
        }

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task2", "gwJoin");
        assertThat(executionsByActivity).doesNotContainKey("task1");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("task2");

        //Move task2
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdTo("task2", "gwJoin")
                .changeState();

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/inclusiveGatewayForkJoin.bpmn20.xml" })
    public void testSetCurrentExecutionToMultipleActivitiesForInclusiveGateway() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startInclusiveGwProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        Execution taskBeforeExecution = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().singleResult();

        List<String> newActivityIds = new ArrayList<>();
        newActivityIds.add("task1");
        newActivityIds.add("task2");
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveSingleExecutionToActivityIds(taskBeforeExecution.getId(), newActivityIds)
                .changeState();

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task1", "task2");
        assertThat(executionsByActivity).doesNotContainKey("gwJoin");

        //Complete one task1
        Optional<Task> task1 = tasks.stream().filter(t -> t.getTaskDefinitionKey().equals("task1")).findFirst();
        if (task1.isPresent()) {
            taskService.complete(task1.get().getId());
        }

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(1);

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task2", "gwJoin");
        assertThat(executionsByActivity).doesNotContainKey("task1");

        assertThat(((ExecutionEntity) executionsByActivity.get("gwJoin").get(0)).isActive()).isFalse();

        taskService.complete(tasks.get(0).getId());

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");
        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/inclusiveGatewayForkJoin.bpmn20.xml" })
    public void testSetMultipleExecutionsToSingleActivityAfterInclusiveGateway() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startInclusiveGwProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        List<String> currentExecutionIds = new ArrayList<>();
        currentExecutionIds.add(executions.get(0).getId());
        currentExecutionIds.add(executions.get(1).getId());
        runtimeService.createChangeActivityStateBuilder()
                .moveExecutionsToSingleActivityId(currentExecutionIds, "taskAfter")
                .changeState();

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");
        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/inclusiveGatewayForkJoin.bpmn20.xml" })
    public void testSetMultipleExecutionsIntoSynchronizingInclusiveGateway() {

        //Move all gateway executions to the synchronizing gateway
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startInclusiveGwProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task1", "task2");

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<String> executionIds = executions.stream().map(Execution::getId).collect(Collectors.toList());

        runtimeService.createChangeActivityStateBuilder()
                .moveExecutionsToSingleActivityId(executionIds, "gwJoin")
                .changeState();

        Execution execution = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().singleResult();
        assertThat(execution.getActivityId()).isEqualTo("taskAfter");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/inclusiveGatewayForkJoin.bpmn20.xml" })
    public void testSetMultipleGatewayExecutionsAndSynchronizingInclusiveGatewayAfterGateway() {

        //Move all gateway executions to the synchronizing gateway
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startInclusiveGwProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task1", "task2");

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        //Complete task1
        for (Task t : tasks) {
            if (t.getTaskDefinitionKey().equals("task1")) {
                taskService.complete(t.getId());
            }
        }

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> classifiedExecutions = groupListContentBy(executions, Execution::getActivityId);
        assertThat(classifiedExecutions.get("task2")).isNotNull();
        assertThat(classifiedExecutions.get("task2")).hasSize(1);
        assertThat(classifiedExecutions.get("gwJoin")).isNotNull();
        assertThat(classifiedExecutions.get("gwJoin")).hasSize(1);

        List<String> executionIds = executions.stream().map(Execution::getId).collect(Collectors.toList());

        runtimeService.createChangeActivityStateBuilder()
                .moveExecutionsToSingleActivityId(executionIds, "taskAfter")
                .changeState();

        Execution execution = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().singleResult();
        assertThat(execution.getActivityId()).isEqualTo("taskAfter");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/inclusiveGatewayForkJoin.bpmn20.xml" })
    public void testSetExecutionIntoSynchronizingInclusiveGatewayFirst() {

        //Move one task to the synchronizing gateway, then complete the remaining task
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startInclusiveGwProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        String executionId = executions.stream().filter(e -> "task1".equals(e.getActivityId())).findFirst().map(Execution::getId).get();
        runtimeService.createChangeActivityStateBuilder()
                .moveExecutionToActivityId(executionId, "gwJoin")
                .changeState();

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task2", "gwJoin");
        assertThat(executionsByActivity).doesNotContainKey("task1");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("task2");

        taskService.complete(task.getId());

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/inclusiveGatewayForkJoin.bpmn20.xml" })
    public void testSetExecutionIntoSynchronizingInclusiveGatewayLast() {

        //Complete one task and then move the last remaining task to the synchronizing gateway
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startInclusiveGwProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        //Complete task1
        Optional<Task> task1 = tasks.stream().filter(t -> t.getTaskDefinitionKey().equals("task1")).findFirst();
        if (task1.isPresent()) {
            taskService.complete(task1.get().getId());
        }

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);
        Map<String, List<Execution>> executionsByActivity = groupListContentBy(executions, Execution::getActivityId);
        assertThat(executionsByActivity).containsKeys("task2", "gwJoin");
        assertThat(executionsByActivity).doesNotContainKey("task1");

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("task2");

        //Move task2 execution
        String executionId = executions.stream().filter(e -> "task2".equals(e.getActivityId())).findFirst().map(Execution::getId).get();
        runtimeService.createChangeActivityStateBuilder()
                .moveExecutionToActivityId(executionId, "gwJoin")
                .changeState();

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");

        taskService.complete(task.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelSubProcesses.bpmn20.xml" })
    public void testSetCurrentActivityToMultipleActivitiesForParallelSubProcesses() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        List<String> newActivityIds = new ArrayList<>();
        newActivityIds.add("subtask");
        newActivityIds.add("subtask2");
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveSingleActivityIdToActivityIds("taskBefore", newActivityIds)
                .changeState();

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(4);

        Optional<Execution> parallelJoinExecution = executions.stream().filter(e -> e.getActivityId().equals("parallelJoin")).findFirst();
        assertThat(parallelJoinExecution.isPresent()).isFalse();

        taskService.complete(tasks.get(0).getId());

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(1);

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(3);

        parallelJoinExecution = executions.stream().filter(e -> e.getActivityId().equals("parallelJoin")).findFirst();
        assertThat(parallelJoinExecution.isPresent()).isTrue();
        assertThat(((ExecutionEntity) parallelJoinExecution.get()).isActive()).isFalse();

        taskService.complete(tasks.get(0).getId());

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");
        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelSubProcesses.bpmn20.xml" })
    public void testSetMultipleActivitiesToSingleActivityAfterParallelSubProcesses() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(4);

        List<String> currentActivityIds = new ArrayList<>();
        currentActivityIds.add("subtask");
        currentActivityIds.add("subtask2");
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdsToSingleActivityId(currentActivityIds, "taskAfter")
                .changeState();

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");
        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/parallelSubProcessesMultipleTasks.bpmn20.xml" })
    public void testMoveCurrentActivityInParallelSubProcess() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(4);

        Execution subProcessExecution = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).activityId("subProcess1")
                .singleResult();
        String subProcessExecutionId = subProcessExecution.getId();
        runtimeService.setVariableLocal(subProcessExecutionId, "subProcessVar", "test");

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdTo("subtask", "subtask2")
                .changeState();

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(4);

        subProcessExecution = runtimeService.createExecutionQuery().executionId(subProcessExecutionId).singleResult();
        assertThat(subProcessExecution).isNotNull();
        assertThat(runtimeService.getVariableLocal(subProcessExecutionId, "subProcessVar")).isEqualTo("test");

        taskService.complete(tasks.get(0).getId());

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(1);

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(3);

        taskService.complete(tasks.get(0).getId());

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");
        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/multipleParallelSubProcesses.bpmn20.xml" })
    public void testSetCurrentActivityToMultipleActivitiesForInclusiveAndParallelSubProcesses() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess", Collections.singletonMap("var1", "test2"));
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        List<String> newActivityIds = new ArrayList<>();
        newActivityIds.add("taskInclusive3");
        newActivityIds.add("subtask");
        newActivityIds.add("subtask3");
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveSingleActivityIdToActivityIds("taskBefore", newActivityIds)
                .changeState();

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(3);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(5);

        Optional<Execution> parallelJoinExecution = executions.stream().filter(e -> e.getActivityId().equals("parallelJoin")).findFirst();
        assertThat(parallelJoinExecution.isPresent()).isFalse();

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("subtask").singleResult();
        taskService.complete(task.getId());

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(3);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("subtask2").singleResult();
        taskService.complete(task.getId());

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(4);

        parallelJoinExecution = executions.stream().filter(e -> e.getActivityId().equals("parallelJoin")).findFirst();
        assertThat(parallelJoinExecution.isPresent()).isTrue();
        assertThat(((ExecutionEntity) parallelJoinExecution.get()).isActive()).isFalse();

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("subtask3").singleResult();
        taskService.complete(task.getId());

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(1);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("taskInclusive3").singleResult();
        taskService.complete(task.getId());

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");
        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/multipleParallelSubProcesses.bpmn20.xml" })
    public void testSetCurrentActivitiesToSingleActivityForInclusiveAndParallelSubProcesses() {
        Map<String, Object> variableMap = new HashMap<>();
        variableMap.put("var1", "test2");
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess", variableMap);
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("taskInclusive1").singleResult();
        assertThat(task).isNotNull();
        taskService.complete(task.getId());

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(3);

        assertThat(taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("taskInclusive3").count()).isEqualTo(1);
        assertThat(taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("subtask").count()).isEqualTo(1);
        assertThat(taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("subtask3").count()).isEqualTo(1);

        List<String> currentActivityIds = new ArrayList<>();
        currentActivityIds.add("taskInclusive3");
        currentActivityIds.add("subtask");
        currentActivityIds.add("subtask3");

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdsToSingleActivityId(currentActivityIds, "taskAfter")
                .changeState();

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(1);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(1);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");
        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Deployment(resources = { "org/flowable/engine/test/api/multipleParallelSubProcesses.bpmn20.xml" })
    public void testSetCurrentActivitiesToSingleActivityInInclusiveGateway() {
        Map<String, Object> variableMap = new HashMap<>();
        variableMap.put("var1", "test2");
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("startParallelProcess", variableMap);
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskBefore");

        taskService.complete(task.getId());

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("taskInclusive1").singleResult();
        assertThat(task).isNotNull();
        taskService.complete(task.getId());

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(3);

        assertThat(taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("taskInclusive3").count()).isEqualTo(1);
        assertThat(taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("subtask").count()).isEqualTo(1);
        assertThat(taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("subtask3").count()).isEqualTo(1);

        List<String> currentActivityIds = new ArrayList<>();
        currentActivityIds.add("subtask");
        currentActivityIds.add("subtask3");

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstance.getId())
                .moveActivityIdsToSingleActivityId(currentActivityIds, "taskInclusive1")
                .changeState();

        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).hasSize(2);

        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(2);

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("taskInclusive3").singleResult();
        taskService.complete(task.getId());

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("taskInclusive1").singleResult();
        taskService.complete(task.getId());

        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).hasSize(5);

        Optional<Execution> inclusiveJoinExecution = executions.stream().filter(e -> e.getActivityId().equals("inclusiveJoin")).findFirst();
        assertThat(inclusiveJoinExecution.isPresent()).isTrue();
        assertThat(((ExecutionEntity) inclusiveJoinExecution.get()).isActive()).isFalse();

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("subtask3").singleResult();
        taskService.complete(task.getId());

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("subtask").singleResult();
        taskService.complete(task.getId());

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).taskDefinitionKey("subtask2").singleResult();
        taskService.complete(task.getId());

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("taskAfter");
        taskService.complete(task.getId());

        assertProcessEnded(processInstance.getId());
    }

}
