package org.continuity.experimentation.experiment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.continuity.experimentation.Experiment;
import org.continuity.experimentation.action.DataInvalidation;
import org.continuity.experimentation.action.ExperimentSummarySaving;
import org.continuity.experimentation.action.RandomSelection;
import org.continuity.experimentation.action.RestartDVDStore;
import org.continuity.experimentation.action.WaitForJmeterReport;
import org.continuity.experimentation.action.continuity.JMeterTestPlanExecution;
import org.continuity.experimentation.action.continuity.JMeterTestPlanExecution.TestPlanBundle;
import org.continuity.experimentation.action.continuity.WorkloadModelGeneration;
import org.continuity.experimentation.action.continuity.WorkloadTransformationAndExecution;
import org.continuity.experimentation.action.inspectit.StartNewRecording;
import org.continuity.experimentation.action.inspectit.StopRecording;
import org.continuity.experimentation.builder.ExperimentBuilder;
import org.continuity.experimentation.data.IDataHolder;
import org.continuity.experimentation.data.SimpleDataHolder;

/**
 * @author Henning Schulz
 *
 */
public class Main {

	private static Experiment experiment;

	public static void main(String[] args) {
		// Experiment builder
		ExperimentBuilder builder = new ExperimentBuilder();

		// Data holders

		IDataHolder<TestPlanBundle> chosenLoadTest = new SimpleDataHolder<TestPlanBundle>("chosen loadtest", TestPlanBundle.class);

		// Set list of available TestPlanBundles
		TestPlanBundle dummyTest = new TestPlanBundle(new File("testplan-4.json"));
		IDataHolder<List<TestPlanBundle>> loadTestList = new SimpleDataHolder<>("loadtest list", new ArrayList<TestPlanBundle>(Arrays.asList(new TestPlanBundle[] { dummyTest })));

		IDataHolder<Date> startTimeDataHolder = new SimpleDataHolder<Date>("start time data", Date.class);
		IDataHolder<Date> stopTimeDataHolder = new SimpleDataHolder<Date>("stop time data", Date.class);

		IDataHolder<String> dataLink = new SimpleDataHolder<String>("OPEN.xtrace data link", String.class);
		IDataHolder<String> workloadLink = new SimpleDataHolder<String>("workload link", String.class);

		IDataHolder<String> tagHolder = new SimpleDataHolder<>("tag", String.class);

		List<IDataHolder<?>> dataHolders = new ArrayList<>(Arrays.asList(new IDataHolder[] { chosenLoadTest, loadTestList, startTimeDataHolder, stopTimeDataHolder, dataLink, workloadLink, tagHolder }));

		tagHolder.set("dvdstore");
		dataLink.set("http://172.16.145.68:8182/rest/open-xtrace/get");

		loadTestList.set(new ArrayList<TestPlanBundle>(Arrays.asList(new TestPlanBundle[] { dummyTest })));

		ExperimentSummarySaving saveSummary = null;

		RandomSelection<TestPlanBundle> randomJmeterTestSelection = new RandomSelection<TestPlanBundle>(loadTestList, chosenLoadTest, true);

		JMeterTestPlanExecution testPlanExecution = new JMeterTestPlanExecution("letslx037", chosenLoadTest);

		StopRecording stopRecording = new StopRecording(stopTimeDataHolder);

		WorkloadModelGeneration workloadModelGeneration = new WorkloadModelGeneration("letslx037", "8080", "wessbas", tagHolder.get(), dataLink, startTimeDataHolder, stopTimeDataHolder, workloadLink);

		WorkloadTransformationAndExecution workloadTransformationAndExecution = new WorkloadTransformationAndExecution("letslx037", "8080", "jmeter", tagHolder, workloadLink, 75, 720l, 40);

		experiment = builder.newExperiment("ICPE 18 LTB").loop(20).append(new RestartDVDStore()).append(randomJmeterTestSelection).append(new StartNewRecording(startTimeDataHolder, false)).append(testPlanExecution)
				.append(new WaitForJmeterReport("letslx037", "8080", "jmeter-report.txt", false, chosenLoadTest)).append(stopRecording).append(workloadModelGeneration).append(new RestartDVDStore())
				.append(workloadTransformationAndExecution).append(new StartNewRecording(startTimeDataHolder, true)).append(new WaitForJmeterReport("letslx037", "8080", "jmeter-report.txt", true, chosenLoadTest))
				.append(stopRecording).append(new DataInvalidation(dataHolders)).end().end().build();

		saveSummary = new ExperimentSummarySaving(new File("").toPath(), experiment);

		experiment.execute();
	}
}