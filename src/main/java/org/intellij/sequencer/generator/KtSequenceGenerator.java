package org.intellij.sequencer.generator;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.util.containers.Stack;
import org.apache.log4j.Level;
import org.intellij.sequencer.Constants;
import org.intellij.sequencer.util.MyPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.descriptors.CallableDescriptor;
import org.jetbrains.kotlin.idea.caches.resolve.ResolutionUtils;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils;
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall;
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode;

import java.util.ArrayList;
import java.util.List;

public class KtSequenceGenerator extends KtVisitorVoid implements IGenerator {
    private final Stack<KtCallExpression> exprStack = new Stack<>();
    private final Stack<CallStack> callStack = new Stack<>();
    private static final Logger LOGGER = Logger.getInstance(KtSequenceGenerator.class.getName());

    private CallStack topStack;
    private CallStack currentStack;
    private int depth;
    private final SequenceParams params;

    public KtSequenceGenerator(SequenceParams params) {
        this.params = params;
//        LOGGER.setLevel(Level.DEBUG);
    }

    @Override
    public CallStack generate(PsiElement psiElement) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[generate]" + psiElement.getText());
        }
        if (psiElement instanceof KtFunction) {
            return generate((KtFunction) psiElement);
        } else if (psiElement instanceof PsiMethod) {
            return generate((PsiMethod) psiElement);
        } else {
            LOGGER.warn("unsupported" + psiElement.getText());
        }

        return topStack;
    }

    public CallStack generate(KtFunction ktFunction) {
        ktFunction.accept(this);
        return topStack;
    }

    public CallStack generate(PsiMethod psiMethod) {
        CallStack javaCall = new SequenceGenerator(params).generate(psiMethod);
        LOGGER.debug("[JAVACall]:" + javaCall.generateText());
        if (topStack == null) {
            topStack = javaCall;
            currentStack = topStack;
        } else {
            currentStack.merge(javaCall);
        }
        return topStack;
    }

    @Override
    public void visitKtElement(@NotNull KtElement element) {
        super.visitKtElement(element);

        element.acceptChildren(this);
    }

    @Override
    public void visitNamedFunction(@NotNull KtNamedFunction function) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[visitNamedFunction]" + function.getText());
        }
        MethodDescription method = createMethod(function);
        if (makeMethodCallExceptCurrentStackIsRecursive(method)) return;
        super.visitNamedFunction(function);
    }

    @Override
    public void visitPrimaryConstructor(@NotNull KtPrimaryConstructor constructor) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[visitPrimaryConstructor]" + constructor.getText());
        }
        MethodDescription method = createMethod(constructor);
        if (makeMethodCallExceptCurrentStackIsRecursive(method)) return;
        super.visitPrimaryConstructor(constructor);
    }

    @Override
    public void visitSecondaryConstructor(@NotNull KtSecondaryConstructor constructor) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[visitSecondaryConstructor]" + constructor.getText());
        }
        MethodDescription method = createMethod(constructor);
        if (makeMethodCallExceptCurrentStackIsRecursive(method)) return;
        super.visitSecondaryConstructor(constructor);
    }

    @Override
    public void visitCallExpression(@NotNull KtCallExpression expression) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[visitCallExpression]" + expression.getText());
        }
        if (MyPsiUtil.isComplexCall(expression)) {
            exprStack.push(expression);
            callStack.push(currentStack);
            super.visitCallExpression(expression);
            if (!exprStack.isEmpty()) {
                CallStack old = currentStack;
                final KtCallExpression pop = exprStack.pop();
                currentStack = callStack.pop();
                resolveAndCall(pop);
                currentStack = old;
            }
        } else {
            resolveAndCall(expression);
            super.visitCallExpression(expression);
        }

    }

    private void resolveAndCall(@NotNull KtCallExpression expression) {
        PsiElement psiElement = resolveFunction(expression);
        if (psiElement instanceof KtClass) {
            currentStack.methodCall(createMethod((KtClass) psiElement));
        } else {
            methodCall(psiElement);
        }
    }


    @Nullable
    private PsiElement resolveFunction(@NotNull KtExpression expression) {
//        ResolvedCall<? extends CallableDescriptor> resolvedCall = ResolutionUtils.resolveToCall(expression, BodyResolveMode.PARTIAL);
//        if (resolvedCall == null) return null;
//        CallableDescriptor candidateDescriptor = resolvedCall.getCandidateDescriptor();
//        return DescriptorToSourceUtils.descriptorToDeclaration(candidateDescriptor);
        return null;
    }

    private void methodCall(PsiElement psiElement) {
        if (psiElement == null) return;
        // if (!params.getMethodFilter().allow(psiMethod)) return;

        if (depth < params.getMaxDepth() - 1) {
            CallStack oldStack = currentStack;
            depth++;
            LOGGER.debug("+ depth = " + depth + " method = " + psiElement.getText());
            generate(psiElement);
            depth--;
            LOGGER.debug("- depth = " + depth + " method = " + psiElement.getText());
            currentStack = oldStack;
        } else {
            currentStack.methodCall(createMethod(psiElement));
        }
    }

    private MethodDescription createMethod(PsiElement psiElement) {
        if (psiElement instanceof KtClass) return createMethod((KtClass) psiElement);
        else if (psiElement instanceof KtNamedFunction) return createMethod((KtNamedFunction) psiElement);
        // todo Something else
        return null;
    }

    private MethodDescription createMethod(KtNamedFunction function) {
        ParamPair paramPair = extractParameters(function.getValueParameters());
        ClassDescription classDescription;
        if (function.isTopLevel()) {
            String filename = function.getContainingKtFile().getName();
            filename = filename.replace(".kt", "_kt");
            classDescription = ClassDescription.getFileNameAsClass(filename); //ClassDescription.TOP_LEVEL_FUN;
        } else if (function.isLocal()) {
            classDescription = ClassDescription.ANONYMOUS_CLASS;
        } else {
            if (function.getFqName() != null) {
                String className = function.getFqName().parent().asString();
                classDescription = new ClassDescription(className, new ArrayList<>());
            } else {
                classDescription = ClassDescription.ANONYMOUS_CLASS;
            }
        }
        List<String> attributes = createAttributes(function.getModifierList());
        String returnType = "Unit";
        if (function.hasDeclaredReturnType()) {
            returnType = getType(function.getTypeReference());
        }

        return MethodDescription.createMethodDescription(
                classDescription,
                attributes, function.getName(), returnType,
                paramPair.argNames, paramPair.argTypes
        );
    }

    private MethodDescription createMethod(KtSecondaryConstructor constructor) {
        ParamPair paramPair = extractParameters(constructor.getValueParameters());
        ClassDescription classDescription = new ClassDescription(constructor.getName(), new ArrayList<>());

        List<String> attributes = createAttributes(constructor.getModifierList());
        String returnType = constructor.getName();

        return MethodDescription.createMethodDescription(
                classDescription,
                attributes, Constants.CONSTRUCTOR_METHOD_NAME, returnType,
                paramPair.argNames, paramPair.argTypes
        );
    }

    private MethodDescription createMethod(KtPrimaryConstructor constructor) {
        ParamPair paramPair = extractParameters(constructor.getValueParameters());
        ClassDescription classDescription = new ClassDescription(constructor.getName(), new ArrayList<>());

        List<String> attributes = createAttributes(constructor.getModifierList());
        String returnType = constructor.getName();

        return MethodDescription.createMethodDescription(
                classDescription,
                attributes, Constants.CONSTRUCTOR_METHOD_NAME, returnType,
                paramPair.argNames, paramPair.argTypes
        );
    }

    private MethodDescription createMethod(KtClass ktClass) {
        ClassDescription classDescription = new ClassDescription(ktClass.getFqName().asString(), new ArrayList<>());
        List<String> attributes = createAttributes(ktClass.getModifierList());
        String returnType = ktClass.getName();

        return MethodDescription.createMethodDescription(
                classDescription,
                attributes, Constants.CONSTRUCTOR_METHOD_NAME, returnType,
                new ArrayList<>(), new ArrayList<>()
        );
    }

    private List<String> createAttributes(KtModifierList modifierList) {

        return new ArrayList<>();
    }

    private ParamPair extractParameters(List<KtParameter> parameters) {
        List<String> argNames = new ArrayList<>();
        List<String> argTypes = new ArrayList<>();
        for (KtParameter parameter : parameters) {
            argNames.add(parameter.getName());
            argTypes.add(getType(parameter.getTypeReference()));
        }
        return new ParamPair(argNames, argTypes);
    }

    /**
     * Get Type reference string.
     *
     * @param typeReference KtTypeReference
     * @return String
     */
    private String getType(@Nullable KtTypeReference typeReference) {
        if (typeReference == null) {
            return "Unit";
        }

        KtTypeElement typeElement = typeReference.getTypeElement();

        if (typeElement instanceof KtNullableType) {
            typeElement = ((KtNullableType) typeElement).getInnerType();
        }

        if (typeElement instanceof KtUserType) {
            return ((KtUserType) typeElement).getReferencedName();
        }

        return "Unit";
    }

    private boolean makeMethodCallExceptCurrentStackIsRecursive(MethodDescription method) {
        if (topStack == null) {
            topStack = new CallStack(method);
            currentStack = topStack;
        } else {
            if (params.isNotAllowRecursion() && currentStack.isRecursive(method))
                return true;
            currentStack = currentStack.methodCall(method);
        }
        return false;
    }

    private static class ParamPair {
        final List<String> argNames;
        final List<String> argTypes;

        public ParamPair(List<String> argNames, List<String> argTypes) {
            this.argNames = argNames;
            this.argTypes = argTypes;
        }
    }
}
