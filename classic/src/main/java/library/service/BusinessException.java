package library.service;

/**
 * 业务异常类，表示用户操作不符合业务要求。
 * 构造方法接收错误提示；提示由父类保存，可通过 getMessage() 读取。
 */
public class BusinessException extends RuntimeException {
    /**
     * 创建一个包含错误提示的业务异常。
     *
     * @param message 错误提示
     */
    public BusinessException(String message) {
        super(message);
    }
}
