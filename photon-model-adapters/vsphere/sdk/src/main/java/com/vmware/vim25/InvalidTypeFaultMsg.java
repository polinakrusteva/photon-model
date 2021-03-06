
package com.vmware.vim25;

import javax.xml.ws.WebFault;


/**
 * This class was generated by Apache CXF 3.1.6
 * 2016-07-18T20:02:09.027+03:00
 * Generated source version: 3.1.6
 */

@WebFault(name = "InvalidTypeFault", targetNamespace = "urn:vim25")
public class InvalidTypeFaultMsg extends Exception {
    public static final long serialVersionUID = 1L;
    
    private com.vmware.vim25.InvalidType invalidTypeFault;

    public InvalidTypeFaultMsg() {
        super();
    }
    
    public InvalidTypeFaultMsg(String message) {
        super(message);
    }
    
    public InvalidTypeFaultMsg(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidTypeFaultMsg(String message, com.vmware.vim25.InvalidType invalidTypeFault) {
        super(message);
        this.invalidTypeFault = invalidTypeFault;
    }

    public InvalidTypeFaultMsg(String message, com.vmware.vim25.InvalidType invalidTypeFault, Throwable cause) {
        super(message, cause);
        this.invalidTypeFault = invalidTypeFault;
    }

    public com.vmware.vim25.InvalidType getFaultInfo() {
        return this.invalidTypeFault;
    }
}
