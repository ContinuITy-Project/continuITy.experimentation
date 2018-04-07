package org.continuity.experimentation.experiment.apichanger;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;
import org.continuity.annotation.dsl.ContinuityModelElement;
import org.continuity.annotation.dsl.WeakReference;
import org.continuity.annotation.dsl.ann.CounterInput;
import org.continuity.annotation.dsl.ann.DirectDataInput;
import org.continuity.annotation.dsl.ann.ExtractedInput;
import org.continuity.annotation.dsl.ann.Input;
import org.continuity.annotation.dsl.ann.InterfaceAnnotation;
import org.continuity.annotation.dsl.ann.ParameterAnnotation;
import org.continuity.annotation.dsl.ann.RegExExtraction;
import org.continuity.annotation.dsl.ann.SystemAnnotation;
import org.continuity.annotation.dsl.system.HttpInterface;
import org.continuity.annotation.dsl.system.HttpParameter;
import org.continuity.annotation.dsl.system.HttpParameterType;
import org.continuity.annotation.dsl.system.ServiceInterface;
import org.continuity.annotation.dsl.system.SystemModel;
import org.continuity.annotation.dsl.visitor.ContinuityModelVisitor;
import org.continuity.annotation.dsl.yaml.ContinuityYamlSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

public class ApiChanger {

	private static final Logger LOGGER = LoggerFactory.getLogger(ApiChanger.class);

	private static final int NUM_ITERATIONS = 18;

	private static final int CHANGE_NUM_LOWER_BOUND = 1;

	private static final int CHANGE_NUM_UPPER_BOUND = 5;

	private static final Path BASE_PATH = Paths.get("heat-clinic", "versions");

	private static final String systemModelFilename = "system-model-heat-clinic.yml";

	private static final String annotationFilename = "annotation-heat-clinic.yml";

	private static final Random RAND = new Random('c' + 'o' + 'n' + 't' + 'i' + 'n' + 'u' + 'I' + 'T' + 'y');

	public static void main(String[] args) throws JsonParseException, JsonMappingException, IOException {
		ContinuityYamlSerializer<SystemModel> systemSerializer = new ContinuityYamlSerializer<>(SystemModel.class);
		ContinuityYamlSerializer<SystemAnnotation> annotationSerializer = new ContinuityYamlSerializer<>(SystemAnnotation.class);

		SystemModel system = systemSerializer.readFromYaml(BASE_PATH.resolve("v2").resolve(systemModelFilename));
		SystemAnnotation annotation = annotationSerializer.readFromYaml(BASE_PATH.resolve("v2").resolve(annotationFilename));

		ApiChanger changer = new ApiChanger(system, annotation, new MarkovTemplateChanger(BASE_PATH.resolve("v2").resolve("allowed-transitions.csv")), ".*csrfToken.*",
				"doLoginUsingPOST_password_REQ_PARAM",
				"doLoginUsingPOST_remember_me_REQ_PARAM",
				"doLoginUsingPOST_username_REQ_PARAM", ".*itemAttribute.*", "Input_extracted_.*");

		changer.addChanges();
	}

	private final SystemModel system;

	private final SystemAnnotation annotation;

	private final List<HttpInterface> addedInterfaces = new ArrayList<>();

	private final List<Pair<HttpInterface, HttpParameter>> addedParameters = new ArrayList<>();

	private final List<String> excludedPatterns;

	private final ContinuityYamlSerializer<ContinuityModelElement> yamlWriter = new ContinuityYamlSerializer<>(ContinuityModelElement.class);

	private final MarkovTemplateChanger templateChanger;

	public ApiChanger(SystemModel system, SystemAnnotation annotation, MarkovTemplateChanger testplanChanger, String... excludedPatterns) {
		this.system = system;
		this.annotation = annotation;
		this.templateChanger = testplanChanger;
		this.excludedPatterns = Arrays.asList(excludedPatterns);
	}

	public void addChanges() {
		List<Integer> changeNumSequence = new ArrayList<>(NUM_ITERATIONS);

		for (int i = 0; i < NUM_ITERATIONS; i++) {
			changeNumSequence.add(RAND.nextInt(CHANGE_NUM_UPPER_BOUND - CHANGE_NUM_LOWER_BOUND) + CHANGE_NUM_LOWER_BOUND);
		}

		int changeSequenceLength = changeNumSequence.stream().reduce((a, b) -> a + b).get();
		LOGGER.info("Change sequence length is {}.", changeSequenceLength);

		List<ApiChangeType> sequence = generateChangeSequence(changeSequenceLength);
		LOGGER.info("Change sequence: {}", sequence);

		int changeIdx = 0;
		int innerIdx = 0;

		LOGGER.info("### v2 -> v3:");
		for (ApiChangeType changeType : sequence) {
			if (innerIdx == changeNumSequence.get(changeIdx)) {
				changeIdx++;
				innerIdx = 0;

				collectChanges(2 + changeIdx);

				LOGGER.info("### v{} -> v{}:", 2 + changeIdx, 3 + changeIdx);
			}

			applyChange(changeType);

			innerIdx++;
		}

		collectChanges(2 + NUM_ITERATIONS);
	}

	private List<ApiChangeType> generateChangeSequence(int length) {
		List<ApiChangeType> sequence = new ArrayList<>(length);

		for (int i = 0; i < length; i++) {
			sequence.add(ApiChangeType.getRandom(RAND.nextDouble()));
		}

		return sequence;
	}

	private void applyChange(ApiChangeType changeType) {
		switch (changeType) {
		case ADD_INTERFACE:
			HttpInterface origInterf = (HttpInterface) selectRandom(system.getInterfaces());
			HttpInterface newInterf = cloneInterface(origInterf);

			addedInterfaces.add(newInterf);
			system.addInterface(newInterf);

			InterfaceAnnotation origInterfAnn = findAnnotation(origInterf);
			if (origInterfAnn != null) {
				annotation.getInterfaceAnnotations().add(cloneInterfaceAnnotation(origInterfAnn, newInterf));
			}

			addToExtractedInputs(newInterf, origInterf);

			templateChanger.copyInterface(origInterf, newInterf);

			LOGGER.info("Cloned interface {} to {}.", origInterf.getId(), newInterf.getId());
			break;
		case ADD_PARAMETER:
			List<Pair<HttpInterface, HttpParameter>> params = system.getInterfaces().stream()
					.flatMap(i -> i.getParameters().stream().filter(this::isIncluded).map(p -> Pair.of((HttpInterface) i, (HttpParameter) p))).collect(Collectors.toList());
			Pair<HttpInterface, HttpParameter> origParam = selectRandom(params);
			HttpParameter newParam = cloneParameter(origParam.getRight());
			newParam.setName(newParam.getName() + "Clone");

			addedParameters.add(origParam);
			origParam.getLeft().addParameter(newParam);

			origInterfAnn = findAnnotation(origParam.getLeft());
			ParameterAnnotation origParamAnn = findAnnotation(origParam.getRight(), origInterfAnn);
			origInterfAnn.addParameterAnnotation(cloneParameterAnnotation(origParamAnn, newParam));

			LOGGER.info("Cloned parameter {} of interface {} to {}.", origParam.getRight().getId(), origParam.getLeft().getId(), newParam.getId());
			break;
		case CHANGE_INTERFACE_PATH:
			HttpInterface interf = (HttpInterface) selectRandom(system.getInterfaces());

			String newPath = createNewPath(interf.getPath() + "/changed");
			interf.setPath(newPath);

			LOGGER.info("Changed path of {} to {}.", interf.getId(), newPath);
			break;
		case CHANGE_PARAMETER_NAME:
			List<HttpParameter> parameters = system.getInterfaces().stream().map(ServiceInterface::getParameters).flatMap(List::stream).map(p -> (HttpParameter) p)
					.filter(p -> p.getParameterType() != HttpParameterType.URL_PART).filter(this::isIncluded).collect(Collectors.toList());
			HttpParameter param = selectRandom(parameters);

			String newName = param.getName() + "-changed";
			param.setName(newName);

			LOGGER.info("Changed name of {} to {}.", param.getId(), newName);
			break;
		case REMOVE_INTERFACE:
			HttpInterface toRemove = selectRandom(addedInterfaces);

			addedInterfaces.remove(toRemove);
			boolean found = system.getInterfaces().remove(toRemove);

			if (!found) {
				LOGGER.warn("There was no interface {} in the system model!", toRemove.getId());
			} else {
				origInterfAnn = findAnnotation(toRemove);
				annotation.getInterfaceAnnotations().remove(origInterfAnn);

				removeFromExtractedInputs(toRemove);
				origInterfAnn.getParameterAnnotations().stream().map(ParameterAnnotation::getInput).forEach(this::removeInputIfUnused);

				templateChanger.removeInterface(toRemove);

				LOGGER.info("Removed interface {}.", toRemove.getId());
			}
			break;
		case REMOVE_PARAMETER:
			Pair<HttpInterface, HttpParameter> paramToRemove = selectRandom(addedParameters);

			paramToRemove.getLeft().getParameters().remove(paramToRemove.getRight());

			origInterfAnn = findAnnotation(paramToRemove.getLeft());
			origParamAnn = findAnnotation(paramToRemove.getRight(), origInterfAnn);
			origInterfAnn.getParameterAnnotations().remove(origParamAnn);
			removeInputIfUnused(origParamAnn.getInput());

			LOGGER.info("Removed parameter {}.", paramToRemove.getRight().getId());
			break;
		default:
			LOGGER.warn("Asked to apply {}!", changeType);
			break;
		}
	}

	private void collectChanges(int version) {
		Path versionDir = BASE_PATH.resolve("v" + version);
		versionDir.toFile().mkdirs();

		try {
			yamlWriter.writeToYaml(system, versionDir.resolve(systemModelFilename));
			yamlWriter.writeToYaml(annotation, versionDir.resolve(annotationFilename));
		} catch (IOException e) {
			LOGGER.error("Exception during writing the system and annotation!", e);
		}

		try {
			templateChanger.getTemplate().writeToFile(versionDir.resolve("allowed-transitions.csv"));
		} catch (IOException e) {
			LOGGER.error("Exception during writing the Markov template!", e);
		}
	}

	private <T> T selectRandom(List<T> list) {
		int randomIndex = RAND.nextInt(list.size());
		return list.get(randomIndex);
	}

	private HttpInterface cloneInterface(HttpInterface origInterf) {
		HttpInterface newInterf = new HttpInterface();
		newInterf.setDomain(origInterf.getDomain());
		newInterf.setEncoding(origInterf.getEncoding());
		newInterf.setHeaders(new ArrayList<>(origInterf.getHeaders()));
		newInterf.setId(createNewId(origInterf.getId() + "_CLONE"));
		newInterf.setMethod(origInterf.getMethod());
		newInterf.setPath(createNewPath(origInterf.getPath() + "/clone"));
		newInterf.setPort(origInterf.getPort());
		newInterf.setProtocol(origInterf.getProtocol());

		for (HttpParameter param : origInterf.getParameters()) {
			HttpParameter newParam = cloneParameter(param);
			newParam.setId(createNewId(newInterf.getId() + "_" + param.getId()));
			newInterf.getParameters().add(newParam);
		}

		return newInterf;
	}

	private HttpParameter cloneParameter(HttpParameter param) {
		HttpParameter newParam = new HttpParameter();
		newParam.setId(createNewId(param.getId() + "_CLONE"));
		newParam.setName(param.getName());
		newParam.setParameterType(param.getParameterType());
		return newParam;
	}

	private boolean isIncluded(ContinuityModelElement element) {
		for (String pattern : excludedPatterns) {
			if ((element.getId() != null) && element.getId().matches(pattern)) {
				return false;
			}
		}

		return true;
	}

	private InterfaceAnnotation findAnnotation(HttpInterface interf) {
		for (InterfaceAnnotation ann : annotation.getInterfaceAnnotations()) {
			if (ann.getAnnotatedInterface().getId().equals(interf.getId())) {
				return ann;
			}
		}

		return null;
	}

	private ParameterAnnotation findAnnotation(HttpParameter param, InterfaceAnnotation interfAnn) {
		for (ParameterAnnotation ann : interfAnn.getParameterAnnotations()) {
			if (ann.getAnnotatedParameter().getId().equals(param.getId())) {
				return ann;
			}
		}

		return null;
	}

	private InterfaceAnnotation cloneInterfaceAnnotation(InterfaceAnnotation origAnn, HttpInterface newInterf) {
		InterfaceAnnotation newAnn = new InterfaceAnnotation();
		newAnn.setAnnotatedInterface(WeakReference.create(newInterf));
		newAnn.setOverrides(new ArrayList<>(origAnn.getOverrides()));

		HttpInterface origInterf = (HttpInterface) origAnn.getAnnotatedInterface().resolve(system);
		List<ParameterAnnotation> paramAnns = new ArrayList<>();

		for (HttpParameter newParam : newInterf.getParameters()) {
			HttpParameter origParam = origInterf.getParameters().stream().filter(p -> p.getName().equals(newParam.getName())).reduce((a, b) -> a).get();

			ParameterAnnotation origParamAnn = findAnnotation(origParam, origAnn);
			ParameterAnnotation newParamAnn = cloneParameterAnnotation(origParamAnn, newParam);

			paramAnns.add(newParamAnn);
		}

		newAnn.setParameterAnnotations(paramAnns);
		return newAnn;
	}

	private ParameterAnnotation cloneParameterAnnotation(ParameterAnnotation origAnn, HttpParameter newParam) {
		ParameterAnnotation newAnn = new ParameterAnnotation();
		newAnn.setAnnotatedParameter(WeakReference.create(newParam));
		newAnn.setOverrides(new ArrayList<>(origAnn.getOverrides()));

		Input origInput = origAnn.getInput();
		Input newInput;

		if (isIncluded(origInput)) {
			newInput = cloneInput(origInput);
			annotation.addInput(newInput);
		} else {
			newInput = origInput;
		}

		newAnn.setInput(newInput);

		return newAnn;
	}

	private void addToExtractedInputs(HttpInterface newInterf, HttpInterface origInterf) {
		List<Pair<ExtractedInput, List<RegExExtraction>>> extractions = annotation.getInputs().stream().filter(in -> in instanceof ExtractedInput).map(in -> (ExtractedInput) in)
				.map(in -> Pair.of(in, in.getExtractions().stream().filter(ex -> ex.getFrom().getId().equals(origInterf.getId())).collect(Collectors.toList())))
				.filter(pair -> !pair.getRight().isEmpty()).collect(Collectors.toList());

		for (Pair<ExtractedInput, List<RegExExtraction>> pair : extractions) {
			for (RegExExtraction origExtraction : pair.getRight()) {
				RegExExtraction newExtraction = new RegExExtraction();
				newExtraction.setFallbackValue(origExtraction.getFallbackValue());
				newExtraction.setFrom(WeakReference.create(newInterf));
				newExtraction.setMatchNumber(origExtraction.getMatchNumber());
				newExtraction.setPattern(origExtraction.getPattern());
				newExtraction.setResponseKey(origExtraction.getResponseKey());
				newExtraction.setTemplate(origExtraction.getResponseKey());

				pair.getLeft().getExtractions().add(newExtraction);
			}
		}
	}

	private void removeFromExtractedInputs(HttpInterface interfToRemove) {
		List<Pair<ExtractedInput, List<RegExExtraction>>> extractions = annotation.getInputs().stream().filter(in -> in instanceof ExtractedInput).map(in -> (ExtractedInput) in)
				.map(in -> Pair.of(in, in.getExtractions().stream().filter(ex -> ex.getFrom().getId().equals(interfToRemove.getId())).collect(Collectors.toList())))
				.filter(pair -> !pair.getRight().isEmpty()).collect(Collectors.toList());

		for (Pair<ExtractedInput, List<RegExExtraction>> pair : extractions) {
			for (RegExExtraction extractionToRemove : pair.getRight()) {
				pair.getLeft().getExtractions().remove(extractionToRemove);
			}
		}
	}

	private Input cloneInput(Input origInput) {
		Input newInput;

		if (origInput instanceof DirectDataInput) {
			newInput = new DirectDataInput();
			((DirectDataInput) newInput).setData(((DirectDataInput) origInput).getData());
		} else if (origInput instanceof CounterInput) {
			CounterInput origCounter = (CounterInput) origInput;
			CounterInput newCounter = new CounterInput();
			newInput = newCounter;

			newCounter.setFormat(origCounter.getFormat());
			newCounter.setIncrement(origCounter.getIncrement());
			newCounter.setMaximum(origCounter.getMaximum());
			newCounter.setScope(origCounter.getScope());
			newCounter.setStart(origCounter.getStart());
		} else if (origInput instanceof ExtractedInput) {
			ExtractedInput origExtr = (ExtractedInput) origInput;
			ExtractedInput newExtr = new ExtractedInput();
			newInput = newExtr;

			newExtr.setInitialValue(origExtr.getInitialValue());

			for (RegExExtraction regex : origExtr.getExtractions()) {
				RegExExtraction newRegex = new RegExExtraction();
				newRegex.setFallbackValue(regex.getFallbackValue());
				newRegex.setFrom(WeakReference.create(regex.getFrom().getType(), regex.getFrom().getId()));
				newRegex.setMatchNumber(regex.getMatchNumber());
				newRegex.setPattern(regex.getPattern());
				newRegex.setResponseKey(regex.getResponseKey());
				newRegex.setTemplate(regex.getTemplate());

				newExtr.getExtractions().add(newRegex);
			}
		} else {
			LOGGER.warn("Cannot handle input {} of type {}!", origInput.getId(), origInput.getClass().getSimpleName());
			newInput = null;
		}

		newInput.setId(createNewId(origInput.getId() + "_CLONE"));
		return newInput;
	}

	private void removeInputIfUnused(Input input) {
		boolean notUsed = annotation.getInterfaceAnnotations().stream().map(InterfaceAnnotation::getParameterAnnotations).flatMap(List::stream)
				.filter(ann -> ann.getInput().getId().equals(input.getId())).collect(Collectors.toList()).isEmpty();

		if (notUsed) {
			annotation.getInputs().remove(input);
		}
	}

	private String createNewId(String initialId) {
		Set<String> usedIds = getUsedIds();
		String currentId = initialId;
		int currentIdx = 1;

		while (usedIds.contains(currentId)) {
			currentIdx++;
			currentId = initialId + "_" + currentIdx;
		}

		return currentId;
	}

	private Set<String> getUsedIds() {
		final Set<String> usedIds = new HashSet<>();
		ContinuityModelVisitor visitor = new ContinuityModelVisitor(elem -> {
			if (elem.getId() != null) {
				usedIds.add(elem.getId());
			}
			return true;
		});

		visitor.visit(system);
		visitor.visit(annotation);

		return usedIds;
	}

	private String createNewPath(String initialPath) {
		Set<String> usedPaths = getUsedPaths();
		String currentPath = initialPath;
		int currentIdx = 1;

		while (usedPaths.contains(currentPath)) {
			currentIdx++;
			currentPath = initialPath + currentIdx;
		}

		return currentPath;
	}

	private Set<String> getUsedPaths() {
		return system.getInterfaces().stream().map(interf -> (HttpInterface) interf).map(HttpInterface::getPath).collect(Collectors.toSet());
	}

}
