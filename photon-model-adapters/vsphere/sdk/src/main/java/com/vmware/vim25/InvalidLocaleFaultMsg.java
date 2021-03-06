
package com.vmware.vim25;

import javax.xml.ws.WebFault;


/**
 * This class was generated by Apache CXF 3.1.6
 * 2016-07-18T20:02:08.951+03:00
 * Generated source version: 3.1.6
 */

@WebFault(name = "InvalidLocaleFault", targetNamespace = "urn:vim25")
public class InvalidLocaleFaultMsg extends Exception {
    public static final long serialVersionUID = 1L;
    
    private com.vmware.vim25.InvalidLocale invalidLocaleFault;

    public InvalidLocaleFaultMsg() {
        super();
    }
    
    public InvalidLocaleFaultMsg(String message) {
        super(message);
    }
    
    public InvalidLocaleFaultMsg(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidLocaleFaultMsg(String message, com.vmware.vim25.InvalidLocale invalidLocaleFault) {
        super(message);
        this.invalidLocaleFault = invalidLocaleFault;
    }

    public InvalidLocaleFaultMsg(String message, com.vmware.vim25.InvalidLocale invalidLocaleFault, Throwable cause) {
        super(message, cause);
        this.invalidLocaleFault = invalidLocaleFault;
    }

    public com.vmware.vim25.InvalidLocale getFaultInfo() {
        return this.invalidLocaleFault;
    }
}
