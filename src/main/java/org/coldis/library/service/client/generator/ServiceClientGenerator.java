package org.coldis.library.service.client.generator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeMirror;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.coldis.library.dto.DtoGenerator;
import org.coldis.library.helper.TypeMirrorHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;

/**
 * Service client generator.
 */
@SupportedSourceVersion(value = SourceVersion.RELEASE_21)
@SupportedAnnotationTypes(value = { "org.coldis.library.service.client.generator.ServiceClient", "org.coldis.library.service.client.generator.ServiceClients" })
public class ServiceClientGenerator extends AbstractProcessor {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceClientGenerator.class);

	/**
	 * Gets the service client operation metadata from an operation getter and a
	 * context.
	 *
	 * @param  operationMethod Operation methods.
	 * @param  context         service client context.
	 * @return                 The service client operation metadata from an
	 *                         operation getter and a context.
	 */
	private static ServiceClientOperation getServiceClientOperationAnno(
			final Element operationMethod,
			final String context) {
		// Gets service client operation metadata annotation.
		ServiceClientOperation serviceClientOperationAnno = operationMethod.getAnnotation(ServiceClientOperation.class);
		// If the service client operation annotation does not match the service client
		// context.
		if ((serviceClientOperationAnno == null) || !serviceClientOperationAnno.context().equals(context)) {
			// Re-sets the service client operation annotation.
			serviceClientOperationAnno = null;
			// Gets service client operations metadata annotation.
			final ServiceClientOperations serviceClientOperationsAnno = operationMethod.getAnnotation(ServiceClientOperations.class);
			// If there is a service client operations annotation.
			if (serviceClientOperationsAnno != null) {
				// For each service client operation metadata annotation.
				for (final ServiceClientOperation currentServiceClientOperationAnno : serviceClientOperationsAnno.operations()) {
					// If the current service client operation annotation matches the service client
					// context.
					if (currentServiceClientOperationAnno.context().equals(context)) {
						// Updates the service client operation metadata annotation.
						serviceClientOperationAnno = currentServiceClientOperationAnno;
					}
				}
			}
		}
		// Returns the operation metadata.
		return serviceClientOperationAnno;
	}

	@Nullable
	private String getRequestMappingPath(
			final String[] path,
			final String[] value) {
		return ArrayUtils.isEmpty(path) ? ArrayUtils.isEmpty(value) ? null : value[0] : path[0];
	}

	/**
	 * Gets the service client operation metadata.
	 *
	 * @param  context         The service client context.
	 * @param  operation       The operation.
	 * @param  defaultOperName The default operation name.
	 * @return                 The service client operation metadata.
	 */
	private ServiceClientOperationMetadata getServiceClientOperationMetadata(
			final String context,
			final ExecutableElement operation,
			final String defaultOperName) {
		// Gets the default operation metadata.
		ServiceClientOperationMetadata serviceClientOperationMetadata = null;
		// Gets the service client operation metadata annotation.
		final ServiceClientOperation serviceClientOperationAnno = ServiceClientGenerator.getServiceClientOperationAnno(operation, context);
		// If the operation should not be ignored.
		if ((serviceClientOperationAnno == null) || ((serviceClientOperationAnno != null) && !serviceClientOperationAnno.ignore())) {
			// Gets the operation original type.
			final TypeMirror operationOriginalReturnType = ((ExecutableType) operation.asType()).getReturnType();
			final List<? extends VariableElement> operationOriginalParamsTypes = operation.getParameters();
			// Gets the operation parameters.
			final List<ServiceClientOperationParameterMetadata> operationParams = new ArrayList<>();
			for (final VariableElement currentOperationParam : operationOriginalParamsTypes) {
				// Operation parameter information.
				final ServiceClientOperationParameterMetadata serviceClientOperationParameterMetadata = new ServiceClientOperationParameterMetadata(
						currentOperationParam.asType().toString(), currentOperationParam.getSimpleName().toString(),
						currentOperationParam.getSimpleName().toString(), ServiceOperationParameterKind.REQUEST_PARAMETER);
				// Gets parameter request mapping.
				final PathVariable pathVariable = currentOperationParam.getAnnotation(PathVariable.class);
				final RequestPart requestPart = currentOperationParam.getAnnotation(RequestPart.class);
				final RequestParam requestParam = currentOperationParam.getAnnotation(RequestParam.class);
				final RequestHeader requestHeader = currentOperationParam.getAnnotation(RequestHeader.class);
				final RequestBody requestBody = currentOperationParam.getAnnotation(RequestBody.class);
				// If there is path variable information.
				if (pathVariable != null) {
					// Sets the parameter name.
					serviceClientOperationParameterMetadata.setName(StringUtils.isBlank(pathVariable.name())
							? (StringUtils.isBlank(pathVariable.value()) ? serviceClientOperationParameterMetadata.getName() : pathVariable.value())
							: pathVariable.name());
					// Sets the parameter kind.
					serviceClientOperationParameterMetadata.setKind(ServiceOperationParameterKind.PATH_VARIABLE);
				}
				// If there is request part information.
				else if (requestPart != null) {
					// Sets the parameter name.
					serviceClientOperationParameterMetadata.setName(StringUtils.isBlank(requestPart.name())
							? (StringUtils.isBlank(requestPart.value()) ? serviceClientOperationParameterMetadata.getName() : requestPart.value())
							: requestPart.name());
					// Sets the parameter kind.
					serviceClientOperationParameterMetadata.setKind(ServiceOperationParameterKind.REQUEST_PART);
				}
				// If there is request parameter information.
				else if (requestParam != null) {
					// Sets the parameter name.
					serviceClientOperationParameterMetadata.setName(StringUtils.isBlank(requestParam.name())
							? (StringUtils.isBlank(requestParam.value()) ? serviceClientOperationParameterMetadata.getName() : requestParam.value())
							: requestParam.name());
					// Sets the parameter kind.
					serviceClientOperationParameterMetadata.setKind(ServiceOperationParameterKind.REQUEST_PARAMETER);
				}
				// If there is request header information.
				else if (requestHeader != null) {
					// Sets the parameter name.
					serviceClientOperationParameterMetadata.setName(StringUtils.isBlank(requestHeader.name())
							? (StringUtils.isBlank(requestHeader.value()) ? serviceClientOperationParameterMetadata.getName() : requestHeader.value())
							: requestHeader.name());
					// Sets the parameter kind.
					serviceClientOperationParameterMetadata.setKind(ServiceOperationParameterKind.REQUEST_HEADER);
				}
				// If there is request body information.
				else if (requestBody != null) {
					// Sets the parameter kind.
					serviceClientOperationParameterMetadata.setKind(ServiceOperationParameterKind.REQUEST_BODY);
				}
				// Gets the operation parameter metadata.
				final ServiceClientOperationParameter serviceClientOperationParameter = currentOperationParam
						.getAnnotation(ServiceClientOperationParameter.class);
				// If there is operation parameter metadata.
				if (serviceClientOperationParameter != null) {
					// Gets the actual operation parameter metadata.
					serviceClientOperationParameterMetadata
							.setType(StringUtils.isBlank(serviceClientOperationParameter.type()) ? serviceClientOperationParameterMetadata.getType()
									: serviceClientOperationParameter.type());
					serviceClientOperationParameterMetadata
							.setName(StringUtils.isBlank(serviceClientOperationParameter.name()) ? serviceClientOperationParameterMetadata.getName()
									: serviceClientOperationParameter.name());
					serviceClientOperationParameterMetadata
							.setKind(serviceClientOperationParameter.kind() != ServiceOperationParameterKind.INHERITED ? serviceClientOperationParameter.kind()
									: serviceClientOperationParameterMetadata.getKind());
				}
				// If the parameter should not be ignored.
				if (!serviceClientOperationParameterMetadata.getKind().equals(ServiceOperationParameterKind.IGNORED)) {
					// Adds the operation parameter metadata do the list.
					operationParams.add(serviceClientOperationParameterMetadata);
				}
			}

			// Copied annotations.
			final List<String> copiedAnnotationsTypesNames = (serviceClientOperationAnno == null ? List.of(Cacheable.class.getName().toString())
					: TypeMirrorHelper.getAnnotationClassesAttribute(serviceClientOperationAnno, "copiedAnnotations"));
			final Set<String> copiedAnnotations = operation.getAnnotationMirrors().stream()
					.filter(annotation -> copiedAnnotationsTypesNames
							.contains(((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().toString()))
					.map(annotation -> annotation.toString()).collect(Collectors.toSet());
			final String reducedCopiedAnnotations = copiedAnnotations.stream().reduce("", StringUtils::join);

			// Gets the default operation metadata.
			serviceClientOperationMetadata = new ServiceClientOperationMetadata(defaultOperName, this.processingEnv.getElementUtils().getDocComment(operation),
					"", "", "", operationOriginalReturnType.toString(), "", reducedCopiedAnnotations, operationParams);
			// Gets the DTOs in operation hierarchy.
			Map<String, String> dtoTypesInOperHier = DtoGenerator.getDtoTypesInHierarchy(operationOriginalReturnType, context, new HashMap<>());
			// For each operation parameter.
			for (final VariableElement currentOperationOriginalParameter : operationOriginalParamsTypes) {
				// Also gets the DTOs in operation hierarchy.
				dtoTypesInOperHier = DtoGenerator.getDtoTypesInHierarchy(currentOperationOriginalParameter.asType(), context, dtoTypesInOperHier);
			}
			// For each other service client in the hierarchy.
			for (final Entry<String, String> dtoTypeInOperHier : dtoTypesInOperHier.entrySet()) {
				// Replaces the original operation type for the correspondent DTO type.
				serviceClientOperationMetadata
						.setReturnType(serviceClientOperationMetadata.getReturnType().replaceAll(dtoTypeInOperHier.getKey(), dtoTypeInOperHier.getValue()));
				// For each operation parameter.
				for (final ServiceClientOperationParameterMetadata currentOperationParameter : serviceClientOperationMetadata.getParameters()) {
					// Replaces the original operation type for the correspondent DTO type.
					currentOperationParameter.setType(currentOperationParameter.getType().replaceAll(dtoTypeInOperHier.getKey(), dtoTypeInOperHier.getValue()));
				}
			}
			// Tries to get the request mapping annotation.
			final RequestMapping requestMapping = operation.getAnnotation(RequestMapping.class);
			final GetMapping getMapping = operation.getAnnotation(GetMapping.class);
			final PostMapping postMapping = operation.getAnnotation(PostMapping.class);
			final PutMapping putMapping = operation.getAnnotation(PutMapping.class);
			final PatchMapping patchMapping = operation.getAnnotation(PatchMapping.class);
			final DeleteMapping deleteMapping = operation.getAnnotation(DeleteMapping.class);

			final String getPath = getMapping != null ? this.getRequestMappingPath(getMapping.path(), getMapping.value()) : null;
			final String postPath = postMapping != null ? this.getRequestMappingPath(postMapping.path(), postMapping.value()) : null;
			final String putPath = putMapping != null ? this.getRequestMappingPath(putMapping.path(), putMapping.value()) : null;
			final String patchPath = patchMapping != null ? this.getRequestMappingPath(patchMapping.path(), patchMapping.value()) : null;
			final String deletePath = deleteMapping != null ? this.getRequestMappingPath(deleteMapping.path(), deleteMapping.value()) : null;
			final String requestPath = requestMapping != null ? this.getRequestMappingPath(requestMapping.path(), requestMapping.value()) : null;
			final String path = getPath != null ? getPath
					: postPath != null ? postPath : putPath != null ? putPath : patchPath != null ? patchPath : deletePath != null ? deletePath : requestPath;

			final RequestMethod method = getMapping != null ? RequestMethod.GET
					: postMapping != null ? RequestMethod.POST
							: putMapping != null ? RequestMethod.PUT
									: patchMapping != null ? RequestMethod.PATCH
											: deleteMapping != null ? RequestMethod.DELETE
													: (requestMapping != null) && !ArrayUtils.isEmpty(requestMapping.method()) ? requestMapping.method()[0]
															: null;

			final String getMediaType = getMapping != null ? !ArrayUtils.isEmpty(getMapping.consumes()) ? getMapping.consumes()[0] : null : null;
			final String postMeditType = postMapping != null ? !ArrayUtils.isEmpty(postMapping.consumes()) ? postMapping.consumes()[0] : null : null;
			final String putMeditType = putMapping != null ? !ArrayUtils.isEmpty(putMapping.consumes()) ? putMapping.consumes()[0] : null : null;
			final String patchMeditType = patchMapping != null ? !ArrayUtils.isEmpty(patchMapping.consumes()) ? patchMapping.consumes()[0] : null : null;
			final String deleteMeditType = deleteMapping != null ? !ArrayUtils.isEmpty(deleteMapping.consumes()) ? deleteMapping.consumes()[0] : null : null;
			final String requestMeditType = requestMapping != null ? !ArrayUtils.isEmpty(requestMapping.consumes()) ? requestMapping.consumes()[0] : null
					: null;
			final String mediaType = getMediaType != null ? getMediaType
					: postMeditType != null ? postMeditType
							: putMeditType != null ? putMeditType
									: patchMeditType != null ? patchMeditType : deleteMeditType != null ? deleteMeditType : requestMeditType;

			// Sets the operation path, method and media type.
			serviceClientOperationMetadata.setPath(path == null ? serviceClientOperationMetadata.getPath() : path);
			serviceClientOperationMetadata.setMethod(method == null ? serviceClientOperationMetadata.getMethod() : method.name());
			serviceClientOperationMetadata.setMediaType(mediaType == null ? serviceClientOperationMetadata.getMediaType() : mediaType);

			// If the operation metadata annotation is present.
			if (serviceClientOperationAnno != null) {
				// Gets the return type value.
				TypeMirror serviceClientOperationReturnTypeValue = null;
				try {
					serviceClientOperationAnno.returnType();
				}
				catch (final MirroredTypeException exception) {
					serviceClientOperationReturnTypeValue = exception.getTypeMirror();
				}
				// Updates the service client operation metadata from the annotation
				// information.
				serviceClientOperationMetadata.setName(
						StringUtils.isBlank(serviceClientOperationAnno.name()) ? serviceClientOperationMetadata.getName() : serviceClientOperationAnno.name());
				serviceClientOperationMetadata.setPath(
						StringUtils.isBlank(serviceClientOperationAnno.path()) ? serviceClientOperationMetadata.getPath() : serviceClientOperationAnno.path());
				serviceClientOperationMetadata.setMethod(StringUtils.isBlank(serviceClientOperationAnno.method()) ? serviceClientOperationMetadata.getMethod()
						: serviceClientOperationAnno.method());
				serviceClientOperationMetadata
						.setMediaType(StringUtils.isBlank(serviceClientOperationAnno.mediaType()) ? serviceClientOperationMetadata.getMediaType()
								: serviceClientOperationAnno.mediaType());
				serviceClientOperationMetadata
						.setReturnType(serviceClientOperationReturnTypeValue.toString().equals("void")
								? (StringUtils.isBlank(serviceClientOperationAnno.returnTypeName()) ? serviceClientOperationMetadata.getReturnType()
										: serviceClientOperationAnno.returnTypeName())
								: serviceClientOperationReturnTypeValue.toString());
				serviceClientOperationMetadata.setAsynchronousDestination(serviceClientOperationAnno.asynchronousDestination());
			}
		}
		// Returns the operation metadata.
		return serviceClientOperationMetadata;
	}

	/**
	 * Gets the service client type metadata.
	 *
	 * @param  originalService           The original type.
	 * @param  serviceClientTypeAnno     The service client type annotation.
	 * @param  alsoGetOperationsMetadata If operations metadata should also be
	 *                                       retrieved.
	 * @return                           The service client type metadata.
	 */
	private ServiceClientMetadata getServiceClientMetadata(
			final TypeElement originalService,
			final ServiceClient serviceClientTypeAnno,
			final Boolean alsoGetOperationsMetadata) {
		// Gets the default type metadata.
		final ServiceClientMetadata serviceClientTypeMetadata = new ServiceClientMetadata(serviceClientTypeAnno.context(), serviceClientTypeAnno.targetPath(),
				serviceClientTypeAnno.templatePath(), serviceClientTypeAnno.fileExtension(), serviceClientTypeAnno.namespace(),
				serviceClientTypeAnno.superclass(),
				(serviceClientTypeAnno.name().isEmpty() ? originalService.getSimpleName() + "Client" : serviceClientTypeAnno.name()),
				this.processingEnv.getElementUtils().getDocComment(originalService), serviceClientTypeAnno.endpoint(), serviceClientTypeAnno.endpointBean(),
				serviceClientTypeAnno.endpointBeanProperty(), serviceClientTypeAnno.serviceClientQualifier(), serviceClientTypeAnno.jmsListenerQualifier(),
				null);
		// TODO Get request mapping information.
		// If operations metadata should also be retrieved.
		if (alsoGetOperationsMetadata) {
			// Operations of service client.
			final List<String> alreadyAddedOperations = new ArrayList<>();
			// Current type. Initially the given one, an then its super types.
			TypeElement currentClass = originalService;
			// For each type in the hierarchy (except Object).
			while ((currentClass != null) && !(currentClass instanceof NoType) && (!Object.class.getName().equals(currentClass.asType().toString()))) {
				// For each class enclosed element.
				for (final Element currentOperation : currentClass.getEnclosedElements()) {
					// If the element is a public method.
					if (currentOperation.getKind().equals(ElementKind.METHOD) && currentOperation.getModifiers().contains(Modifier.PUBLIC)
							&& (!currentOperation.getModifiers().contains(Modifier.STATIC))) {
						// Gets the default operation name.
						final String operationName = currentOperation.getSimpleName().toString();
						// If the operation has not been added yet (for override operations).
						if (!alreadyAddedOperations.contains(currentOperation.toString()) && (!"class".equals(operationName))) {
							// Gets the operation metadata.
							final ServiceClientOperationMetadata serviceClientOperationMetadata = this.getServiceClientOperationMetadata(
									serviceClientTypeMetadata.getContext(), (ExecutableElement) currentOperation, operationName);
							// If the operation metadata is retrieved.
							if (serviceClientOperationMetadata != null) {
								// Adds the service client operation for later conversion.
								serviceClientTypeMetadata.getOperations().add(serviceClientOperationMetadata);
							}
							// Adds the operation to the already added list.
							alreadyAddedOperations.add(currentOperation.toString());
						}
					}
				}
				// The current class is the late class superclass.
				currentClass = currentClass.getSuperclass() instanceof DeclaredType ? (TypeElement) ((DeclaredType) currentClass.getSuperclass()).asElement()
						: null;
			}
		}
		// Returns the type metadata.
		return serviceClientTypeMetadata;
	}

	/**
	 * Generates a service client from a original type.
	 *
	 * @param  originalService           Original type information.
	 * @param  serviceClientTypeMetadata service client type metadata.
	 * @throws IOException               If the class cannot be generated.
	 */
	private void generateServiceClient(
			final TypeElement originalService,
			final ServiceClientMetadata serviceClientTypeMetadata) throws IOException {
		// Gets the velocity engine.
		final VelocityEngine velocityEngine = new VelocityEngine();
		// Configures the resource loader to also look at the classpath.
		velocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
		velocityEngine.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
		// Initializes the velocity engine.
		velocityEngine.init();
		// Creates a new velocity context and sets its variables.
		final VelocityContext velocityContext = new VelocityContext();
		velocityContext.put("serviceClient", serviceClientTypeMetadata);
		velocityContext.put("newLine", "\r\n");
		velocityContext.put("tab", "\t");
		velocityContext.put("h", "#");
		// Gets the template for the service client.
		final Template serviceClientTemplate = velocityEngine.getTemplate(serviceClientTypeMetadata.getTemplatePath());
		// Prepares the writer for the service client.
		final File serviceClientFile = new File(serviceClientTypeMetadata.getTargetPath() + File.separator + serviceClientTypeMetadata.getFileNamespace(),
				serviceClientTypeMetadata.getName() + "." + serviceClientTypeMetadata.getFileExtension());
		FileUtils.forceMkdir(serviceClientFile.getParentFile());
		final Writer serviceClientWriter = new FileWriter(serviceClientFile);
		// Writes the generated class code.
		serviceClientTemplate.merge(velocityContext, serviceClientWriter);
		// Closes the class writer.
		serviceClientWriter.close();
	}

	/**
	 * Generates the service client from type and metadata.
	 *
	 * @param originalService       Original type generating the service client.
	 * @param serviceClientMetadata service client metadata.
	 */
	private void generateServiceClient(
			final TypeElement originalService,
			final ServiceClient serviceClientMetadata) {
		// Gets the service client metadata.
		final ServiceClientMetadata serviceClientTypeMetadata = this.getServiceClientMetadata(originalService, serviceClientMetadata, true);
		// Tries to generate the service clients.
		try {
			// Generates the classes.
			ServiceClientGenerator.LOGGER.debug("Generating service client " + serviceClientTypeMetadata.getName() + ".");
			this.generateServiceClient(originalService, serviceClientTypeMetadata);
			ServiceClientGenerator.LOGGER.debug("Service client " + serviceClientTypeMetadata.getName() + " created successfully.");
		}
		// If there is a problem generating the service clients.
		catch (final IOException exception) {
			// Logs it.
			ServiceClientGenerator.LOGGER.error("Service client " + serviceClientTypeMetadata.getName() + " not created successfully.", exception);
		}
	}

	/**
	 * @see jakarta.annotation.processing.AbstractProcessor#process(java.util.Set,
	 *      jakarta.annotation.processing.RoundEnvironment)
	 */
	@Override
	@SuppressWarnings("unchecked")
	public boolean process(
			final Set<? extends TypeElement> annotations,
			final RoundEnvironment roundEnv) {
		ServiceClientGenerator.LOGGER.debug("Initializing service clients generation...");
		// For each type generating multiple service clients.
		for (final TypeElement originalService : (Set<TypeElement>) roundEnv.getElementsAnnotatedWith(ServiceClients.class)) {
			// Gets the service clients metadata.
			final ServiceClients serviceClientsMetadata = originalService.getAnnotation(ServiceClients.class);
			// For each service client metadata.
			for (final ServiceClient serviceClientMetadata : serviceClientsMetadata.types()) {
				// Generates the service client.
				this.generateServiceClient(originalService, serviceClientMetadata);
			}
		}
		// For each type generating a single service client.
		for (final TypeElement originalService : (Set<TypeElement>) roundEnv.getElementsAnnotatedWith(ServiceClient.class)) {
			// Gets the service client metadata.
			final ServiceClient serviceClientMetadata = originalService.getAnnotation(ServiceClient.class);
			// Generates the service client.
			this.generateServiceClient(originalService, serviceClientMetadata);
		}
		// Mark that the message sources annotations have been processed.
		ServiceClientGenerator.LOGGER.debug("Finishing ServiceClientGenerator...");
		return true;
	}

}
