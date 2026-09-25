package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 选课确认阶段的学分汇总。 */
public class CreditConfirmationSummary {
    @SerializedName("xswqrxf") private String unconfirmedCredits;
    @SerializedName("xsyqrxf") private String confirmedCredits;
    @SerializedName("xscxzxf") private String retakenCredits;
    @SerializedName("xkzxf") private String selectableCredits;
    @SerializedName("zczxf") private String selectedCredits;
    @SerializedName("xfqrsj") private String confirmationWindow;

    public String getUnconfirmedCredits() { return unconfirmedCredits; }
    public String getConfirmedCredits() { return confirmedCredits; }
    public String getRetakenCredits() { return retakenCredits; }
    public String getSelectableCredits() { return selectableCredits; }
    public String getSelectedCredits() { return selectedCredits; }
    public String getConfirmationWindow() { return confirmationWindow; }
}
