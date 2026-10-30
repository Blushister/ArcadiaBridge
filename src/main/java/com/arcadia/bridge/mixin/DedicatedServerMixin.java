

    @Inject(method = "enforceSecureProfile", at = @At("HEAD"), cancellable = true)
    private void arcadiabridge$noSecureProfile(CallbackInfoReturnable<Boolean> cir) {
        if (BridgeConfig.CHAT_NO_REPORTS.get()) {
            cir.setReturnValue(false);
        }
    }
}
