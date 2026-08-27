package haven;

public enum Theme {
    Pretty(DecoX.DecoThemeType.Big),
    Small(DecoX.DecoThemeType.Small),
    Ard(DecoX.DecoThemeType.Ard);
    
    public final DecoX.DecoThemeType deco;
    
    Theme(DecoX.DecoThemeType deco) {
        this.deco = deco;
    }

    /** Ard widget chrome (buttons, window controls). HUD layout is CFG.FLOATING_HUD. */
    public boolean usesFloatingHud() {
	return this == Ard;
    }
}
