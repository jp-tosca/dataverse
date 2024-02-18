package edu.harvard.iq.dataverse.api;

import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import io.github.amithkoujalgi.ollama4j.core.models.OllamaResult;
import io.github.amithkoujalgi.ollama4j.core.types.OllamaModelType;
import io.github.amithkoujalgi.ollama4j.core.utils.OptionsBuilder;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

@Path("llama")
public class Llama extends AbstractApiBean{

    @GET
    @Path("ask")
    public Response talkToLlama() {
        
        try {
            OllamaAPI ollama = new OllamaAPI("http://ollama:11434/");
            //OptionsBuilder          
            OllamaResult result = ollama.generate(OllamaModelType.LLAMA2, "How are you doing today?", new OptionsBuilder().build());
            return ok("Llama says: " + result.getResponse());

        } catch (Exception e) {
            e.printStackTrace(); 
            return error(Status.CONFLICT, e.getMessage());      
        }
        
    }

    @GET
    @Path("pull/{modelName}")
    public Response pullModel(@PathParam("modelName") String modelName) {
        
        try {
            OllamaAPI ollama = new OllamaAPI("http://ollama:11434/");
            ollama.pullModel(modelName);          
            return ok("pulled model: " + modelName);
        } catch (Exception e) {
            e.printStackTrace(); 
            return error(Status.CONFLICT, e.getMessage());      
        }
        
    }
    
}
