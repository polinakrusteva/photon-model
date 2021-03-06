
package com.vmware.vim25;

import javax.xml.ws.WebFault;


/**
 * This class was generated by Apache CXF 3.1.6
 * 2016-07-18T20:02:08.988+03:00
 * Generated source version: 3.1.6
 */

@WebFault(name = "PatchInstallFailedFault", targetNamespace = "urn:vim25")
public class PatchInstallFailedFaultMsg extends Exception {
    public static final long serialVersionUID = 1L;
    
    private com.vmware.vim25.PatchInstallFailed patchInstallFailedFault;

    public PatchInstallFailedFaultMsg() {
        super();
    }
    
    public PatchInstallFailedFaultMsg(String message) {
        super(message);
    }
    
    public PatchInstallFailedFaultMsg(String message, Throwable cause) {
        super(message, cause);
    }

    public PatchInstallFailedFaultMsg(String message, com.vmware.vim25.PatchInstallFailed patchInstallFailedFault) {
        super(message);
        this.patchInstallFailedFault = patchInstallFailedFault;
    }

    public PatchInstallFailedFaultMsg(String message, com.vmware.vim25.PatchInstallFailed patchInstallFailedFault, Throwable cause) {
        super(message, cause);
        this.patchInstallFailedFault = patchInstallFailedFault;
    }

    public com.vmware.vim25.PatchInstallFailed getFaultInfo() {
        return this.patchInstallFailedFault;
    }
}
