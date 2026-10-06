package ch.regdata.rps.engine.client.enginecontext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Paths;
import java.util.HashMap;

public class RPSEngineContextJsonFileProvider extends RPSEngineContextProviderBase {
    private final String _rightsContextsFilePath;
    private final String _processingContextsFilePath;

    public RPSEngineContextJsonFileProvider(String rightsContextsFilePath, String processingContextsFilePath) {
        _rightsContextsFilePath = rightsContextsFilePath;
        _processingContextsFilePath = processingContextsFilePath;
    }

    private static HashMap getDictionaryFromJsonFile(String filePath, Class clazz) {
        try {
            ObjectMapper mapper = JsonMapper.builder()
                    .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                    .build();
            if (clazz == ProcessingContext.class) {
                return mapper.readValue(Paths.get(filePath).toFile(), new TypeReference<HashMap<String, ProcessingContext>>() {
                });
            }
            if (clazz == RightContext.class) {
                return mapper.readValue(Paths.get(filePath).toFile(), new TypeReference<HashMap<String, RightContext>>() {
                });
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    protected HashMap<String, RightContext> getRightsContexts() {
        HashMap<String, RightContext> map = getDictionaryFromJsonFile(_rightsContextsFilePath, RightContext.class);
        return map;
    }

    @Override
    protected HashMap<String, ProcessingContext> getProcessingContexts() {
        HashMap<String, ProcessingContext> map = getDictionaryFromJsonFile(_processingContextsFilePath, ProcessingContext.class);
        return map;
    }
}
