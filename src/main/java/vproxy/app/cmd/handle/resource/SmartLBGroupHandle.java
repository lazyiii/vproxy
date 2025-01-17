package vproxy.app.cmd.handle.resource;

import vproxy.app.Application;
import vproxy.app.Config;
import vproxy.app.cmd.Command;
import vproxy.app.cmd.Param;
import vproxy.app.cmd.Resource;
import vproxy.app.cmd.ResourceType;
import vproxy.app.cmd.handle.param.ServiceHandle;
import vproxy.app.cmd.handle.param.ZoneHandle;
import vproxy.app.mesh.SmartLBGroupHolder;
import vproxy.component.app.TcpLB;
import vproxy.component.auto.SmartLBGroup;
import vproxy.component.exception.NotFoundException;
import vproxy.component.exception.XException;
import vproxy.component.svrgroup.ServerGroup;

import java.util.LinkedList;
import java.util.List;

public class SmartLBGroupHandle {
    private SmartLBGroupHandle() {
    }

    public static void check(Resource parent) throws XException {
        if (parent != null)
            throw new XException(ResourceType.slg.fullname + " is on top level");
    }

    public static void checkCreate(Command cmd) throws XException {
        if (!Config.serviceMeshConfigProvided) {
            throw new XException("service mesh config not provided, so the smart-lb-group cannot be created");
        }
        if (!cmd.args.containsKey(Param.service))
            throw new XException("missing argument " + Param.service.fullname);
        if (!cmd.args.containsKey(Param.zone))
            throw new XException("missing argument " + Param.zone.fullname);
        if (!cmd.args.containsKey(Param.tl))
            throw new XException("missing argument " + Param.tl.fullname);
        if (!cmd.args.containsKey(Param.sg))
            throw new XException("missing argument " + Param.sg.fullname);
    }

    public static List<String> names() {
        return Application.get().smartLBGroupHolder.names();
    }

    public static List<SmartLBGroup> detail() {
        SmartLBGroupHolder holder = Application.get().smartLBGroupHolder;
        List<String> names = holder.names();
        List<SmartLBGroup> smartLBGroups = new LinkedList<>();
        for (String name : names) {
            try {
                SmartLBGroup s = holder.get(name);
                smartLBGroups.add(s);
            } catch (NotFoundException ignore) {
            }
        }
        return smartLBGroups;
    }

    public static void remove(Command cmd) throws NotFoundException {
        String alias = cmd.resource.alias;
        Application.get().smartLBGroupHolder.remove(alias);
    }

    public static void add(Command cmd) throws Exception {
        String alias = cmd.resource.alias;
        String service = ServiceHandle.get(cmd);
        String zone = ZoneHandle.get(cmd);
        TcpLB tl = Application.get().tcpLBHolder.get(cmd.args.get(Param.tl));
        ServerGroup sg = Application.get().serverGroupHolder.get(cmd.args.get(Param.sg));

        Application.get().smartLBGroupHolder.add(alias, service, zone, tl, sg);
    }
}
