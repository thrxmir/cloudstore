package co.codewizards.cloudstore.ls.rest.server.service;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import co.codewizards.cloudstore.ls.core.invoke.ClassInfo;
import co.codewizards.cloudstore.ls.core.provider.MediaTypeConst;

@Path("ClassInfo")
@Consumes(MediaTypeConst.APPLICATION_JAVA_NATIVE)
@Produces(MediaTypeConst.APPLICATION_JAVA_NATIVE)
public class ClassInfoService extends AbstractService {

	@GET
	@Path("{classId}")
	public ClassInfo getClassInfo(@PathParam("classId") int classId) {
//		final ClassManager classManager = getObjectManager().getClassManager();
//		final ClassInfo classInfo = classManager.getClassInfo(classId);
//		return classInfo;
		throw new UnsupportedOperationException();
	}

}
