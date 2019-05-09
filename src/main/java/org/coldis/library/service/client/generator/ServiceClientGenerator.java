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
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeMirror;

import org.apache.commons.io.FileUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.coldis.library.dto.DtoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service client generator.
 */
@SupportedSourceVersion(value = SourceVersion.RELEASE_11)
@SupportedAnnotationTypes(value = { "org.coldis.library.service.client.generator.ServiceClient",
"org.coldis.library.service.client.generator.ServiceClients" })
public class ServiceClientGenerator extends AbstractProcessor {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceClientGenerator.class);

	/**
	 * Generates a service client from a original type.
	 *
	 * @param  originalService           Original type information.
	 * @param  serviceClientTypeMetadata service client type metadata.
	 * @throws IOException               If the class cannot be generated.
	 */
	private void generateServiceClient(final TypeElement originalService,
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
		final File serviceClientFile = new File(
				serviceClientTypeMetadata.getTargetPath() + File.separator
				+ serviceClientTypeMetadata.getFileNamespace(),
				serviceClientTypeMetadata.getName() + "." + serviceClientTypeMetadata.getFileExtension());
		FileUtils.forceMkdir(serviceClientFile.getParentFile());
		final Writer serviceClientWriter = new FileWriter(serviceClientFile);
		// Writes the generated class code.
		serviceClientTemplate.merge(velocityContext, serviceClientWriter);
		// Closes the class writer.
		serviceClientWriter.close();
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
	private ServiceClientMetadata getServiceClientMetadata(final TypeElement originalService,
			final ServiceClient serviceClientTypeAnno, final Boolean alsoGetOperationsMetadata) {
		// Gets the default type metadata.
		final ServiceClientMetadata serviceClientTypeMetadata = new ServiceClientMetadata(
				serviceClientTypeAnno.context(), serviceClientTypeAnno.targetPath(),
				serviceClientTypeAnno.templatePath(), serviceClientTypeAnno.fileExtension(),
				serviceClientTypeAnno.namespace(), serviceClientTypeAnno.superclass(),
				serviceClientTypeAnno.name().isEmpty() ? originalService.getSimpleName() + "Client"
						: serviceClientTypeAnno.name(),
						this.processingEnv.getElementUtils().getDocComment(originalService), serviceClientTypeAnno.endpoint(),
						null);
		// If operations metadata should also be retrieved.
		if (alsoGetOperationsMetadata) {
			// Operations of service client.
			final List<String> alreadyAddedOperations = new ArrayList<>();
			// Current type. Initially the given one, an then its super types.
			TypeElement currentClass = originalService;
			// For each type in the hierarchy (except Object).
			while ((currentClass != null) && !(currentClass instanceof NoType)
					&& (!Object.class.getName().equals(currentClass.asType().toString()))) {
				// For each class enclosed element.
				for (final Element currentOperation : currentClass.getEnclosedElements()) {
					// If the element is a public method.
					if (currentOperation.getKind().equals(ElementKind.METHOD)
							&& currentOperation.getModifiers().contains(Modifier.PUBLIC)
							&& (!currentOperation.getModifiers().contains(Modifier.STATIC))) {
						// Gets the default operation name.
						final String operationName = currentOperation.getSimpleName().toString();
						// If the operation has not been added yet (for override operations).
						if (!alreadyAddedOperations.contains(currentOperation.getSimpleName().toString())
								&& (!"class".equals(operationName))) {
							// Gets the operation metadata.
							final ServiceClientOperationMetadata serviceClientOperationMetadata = this
									.getServiceClientOperationMetadata(serviceClientTypeMetadata.getContext(),
											(ExecutableElement) currentOperation, operationName);
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
				currentClass = currentClass.getSuperclass() instanceof DeclaredType
						? (TypeElement) ((DeclaredType) currentClass.getSuperclass()).asElement()
								: null;
			}
		}
		// Returns the type metadata.
		return serviceClientTypeMetadata;
	}

	/**
	 * Gets the service client operation metadata from an operation getter and a
	 * context.
	 *
	 * @param  operationGetter Operation getter.
	 * @param  context         service client context.
	 * @return                 The service client operation metadata from an
	 *                         operation getter and a context.
	 */
	private static ServiceClientOperation getServiceClientOperationAnno(final Element operationGetter,
			final String context) {
		// Gets service client operation metadata annotation.
		ServiceClientOperation serviceClientOperationAnno = operationGetter.getAnnotation(ServiceClientOperation.class);
		// If the service client operation annotation does not match the service client
		// context.
		if ((serviceClientOperationAnno == null) || !serviceClientOperationAnno.context().equals(context)) {
			// Re-sets the service client operation annotation.
			serviceClientOperationAnno = null;
			// Gets service client operations metadata annotation.
			final ServiceClientOperations serviceClientOperationsAnno = operationGetter
					.getAnnotation(ServiceClientOperations.class);
			// If there is a service client operations annotation.
			if (serviceClientOperationsAnno != null) {
				// For each service client operation metadata annotation.
				for (final ServiceClientOperation currentServiceClientOperationAnno : serviceClientOperationsAnno
						.operations()) {
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

	/**
	 * Gets the service client operation metadata.
	 *
	 * @param  context         The service client context.
	 * @param  operation       The operation.
	 * @param  defaultOperName The default operation name.
	 * @return                 The service client operation metadata.
	 */
	private ServiceClientOperationMetadata getServiceClientOperationMetadata(final String context,
			final ExecutableElement operation, final String defaultOperName) {
		// Gets the default operation metadata.
		ServiceClientOperationMetadata serviceClientOperationMetadata = null;
		// Gets the service client operation metadata annotation.
		final ServiceClientOperation serviceClientOperationAnno = ServiceClientGenerator
				.getServiceClientOperationAnno(operation, context);
		// If the operation should not be ignored.
		if ((serviceClientOperationAnno == null)
				|| ((serviceClientOperationAnno != null) && !serviceClientOperationAnno.ignore())) {
			// Gets the operation original type.
			final TypeMirror operationOriginalReturnType = ((ExecutableType) operation.asType()).getReturnType();
			final List<? extends VariableElement> operationOriginalParamsTypes = operation.getParameters();
			// Gets the operation parameters.
			final List<ServiceClientOperationParameterMetadata> operationParams = new ArrayList<>();
			for (final VariableElement currentOperationParam : operationOriginalParamsTypes) {
				// Gets the operation parameter metadata.
				final ServiceClientOperationParameter serviceClientOperationParameter = currentOperationParam
						.getAnnotation(ServiceClientOperationParameter.class);
				// Gets the actual operation parameter metadata.
				final String operationParamType = (serviceClientOperationParameter != null)
						&& !serviceClientOperationParameter.type().isEmpty() ? serviceClientOperationParameter.type()
								: currentOperationParam.asType().toString();
						final String operationParamName = (serviceClientOperationParameter != null)
								&& !serviceClientOperationParameter.name().isEmpty() ? serviceClientOperationParameter.name()
										: currentOperationParam.getSimpleName().toString();
								final String operationParamKind = (serviceClientOperationParameter != null)
										? serviceClientOperationParameter.kind()
												: "";
										// Adds the operation parameter metadata do the list.
										operationParams.add(new ServiceClientOperationParameterMetadata(operationParamType, operationParamName,
												operationParamKind));
			}
			// Gets the default operation metadata.
			serviceClientOperationMetadata = new ServiceClientOperationMetadata(defaultOperName,
					this.processingEnv.getElementUtils().getDocComment(operation), "", "", "",
					operationOriginalReturnType.toString(), operationParams, false);
			// Gets the DTOs in operation hierarchy.
			Map<String, String> dtoTypesInOperHier = DtoGenerator.getDtoTypesInHierarchy(operationOriginalReturnType,
					context, new HashMap<>());
			// For each operation parameter.
			for (final VariableElement currentOperationOriginalParameter : operationOriginalParamsTypes) {
				// Also gets the DTOs in operation hierarchy.
				dtoTypesInOperHier = DtoGenerator.getDtoTypesInHierarchy(currentOperationOriginalParameter.asType(),
						context, dtoTypesInOperHier);
			}
			// For each other service client in the hierarchy.
			for (final Entry<String, String> dtoTypeInOperHier : dtoTypesInOperHier.entrySet()) {
				// Replaces the original operation type for the correspondent DTO type.
				serviceClientOperationMetadata.setReturnType(serviceClientOperationMetadata.getReturnType()
						.replaceAll(dtoTypeInOperHier.getKey(), dtoTypeInOperHier.getValue()));
				// For each operation parameter.
				for (final ServiceClientOperationParameterMetadata currentOperationParameter : serviceClientOperationMetadata
						.getParameters()) {
					// Replaces the original operation type for the correspondent DTO type.
					currentOperationParameter.setType(currentOperationParameter.getType()
							.replaceAll(dtoTypeInOperHier.getKey(), dtoTypeInOperHier.getValue()));
				}
			}
			// If the operation metadata annotation is present.
			if (serviceClientOperationAnno != null) {
				// Updates the service client operation metadata from the annotation
				// information.
				serviceClientOperationMetadata
				.setName(serviceClientOperationAnno.name().isEmpty() ? serviceClientOperationMetadata.getName()
						: serviceClientOperationAnno.name());
				serviceClientOperationMetadata
				.setPath(serviceClientOperationAnno.path().isEmpty() ? serviceClientOperationMetadata.getPath()
						: serviceClientOperationAnno.path());
				serviceClientOperationMetadata.setMethod(
						serviceClientOperationAnno.method().isEmpty() ? serviceClientOperationMetadata.getMethod()
								: serviceClientOperationAnno.method());
				serviceClientOperationMetadata.setMediaType(
						serviceClientOperationAnno.mediaType().isEmpty() ? serviceClientOperationMetadata.getMediaType()
								: serviceClientOperationAnno.mediaType());
				serviceClientOperationMetadata.setReturnType(serviceClientOperationAnno.returnType().isEmpty()
						? serviceClientOperationMetadata.getReturnType()
								: serviceClientOperationAnno.returnType());
				serviceClientOperationMetadata.setAsynchronous(serviceClientOperationAnno.asynchronous());
			}
		}
		// Returns the operation metadata.
		return serviceClientOperationMetadata;
	}

	/**
	 * Generates the service client from type and metadata.
	 *
	 * @param originalService       Original type generating the service client.
	 * @param serviceClientMetadata service client metadata.
	 */
	private void generateServiceClient(final TypeElement originalService, final ServiceClient serviceClientMetadata) {
		// Gets the service client metadata.
		final ServiceClientMetadata serviceClientTypeMetadata = this.getServiceClientMetadata(originalService,
				serviceClientMetadata, true);
		// Tries to generate the service clients.
		try {
			// Generates the classes.
			ServiceClientGenerator.LOGGER
			.debug("Generating service client " + serviceClientTypeMetadata.getName() + ".");
			this.generateServiceClient(originalService, serviceClientTypeMetadata);
			ServiceClientGenerator.LOGGER
			.debug("Service client " + serviceClientTypeMetadata.getName() + " created successfully.");
		}
		// If there is a problem generating the service clients.
		catch (final IOException exception) {
			// Logs it.
			ServiceClientGenerator.LOGGER.error(
					"Service client " + serviceClientTypeMetadata.getName() + " not created successfully.", exception);
		}
	}

	/**
	 * @see javax.annotation.processing.AbstractProcessor#process(java.util.Set,
	 *      javax.annotation.processing.RoundEnvironment)
	 */
	@Override
	@SuppressWarnings("unchecked")
	public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
		ServiceClientGenerator.LOGGER.debug("Initializing service clients generation...");
		// For each type generating multiple service clients.
		for (final TypeElement originalService : (Set<TypeElement>) roundEnv
				.getElementsAnnotatedWith(ServiceClients.class)) {
			// Gets the service clients metadata.
			final ServiceClients serviceClientsMetadata = originalService.getAnnotation(ServiceClients.class);
			// For each service client metadata.
			for (final ServiceClient serviceClientMetadata : serviceClientsMetadata.types()) {
				// Generates the service client.
				this.generateServiceClient(originalService, serviceClientMetadata);
			}
		}
		// For each type generating a single service client.
		for (final TypeElement originalService : (Set<TypeElement>) roundEnv
				.getElementsAnnotatedWith(ServiceClient.class)) {
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
